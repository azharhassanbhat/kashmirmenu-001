import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { kitchenApi, notificationApi, serviceRequestApi } from '../../api';
import type { AppNotification, PlatformRealtimeEvent, RealtimeMessage } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import {
  DEFAULT_TOAST_MS,
  EMPTY_TOASTS,
  PLATFORM_QUERY_KEYS,
  RESTAURANT_QUERY_KEYS,
  baselineFrom,
  createCoalescer,
  createDecisionDeduper,
  createSeenIds,
  decidePlatformEvent,
  planLiveMessage,
  detectStatusTransition,
  dismissToast,
  isPlatformEvent,
  isRealtimeMessage,
  isRestaurantStaff,
  notificationScope,
  planCatchUp,
  pushToast,
  statusEventCopy,
  toastForMessage,
  type NotificationScope,
  type ToastInput,
  type ToastState,
} from '../../lib/notificationCenter';
import { RESTAURANT_STATUS_TYPES, roleHears, ruleFor, ruleForEvent, type EventRule } from '../../lib/sound/eventMap';
import { createReminderLoop, reminderTargets, type ReminderLoop } from '../../lib/sound/reminders';
import { attachGestureUnlock } from '../../lib/sound/gestureUnlock';
import type { SoundKind } from '../../lib/sound/soundDefs';
import { soundClaims } from '../../lib/sound/soundClaims';
import { soundEngine, type EngineState } from '../../lib/sound/soundEngine';
import {
  DEFAULT_PREFS,
  audible,
  loadPrefs,
  prefsKey,
  safeStorage,
  sanitizePrefs,
  savePrefs,
  type SoundPrefs,
} from '../../lib/sound/soundPrefs';
import {
  COLLAPSE_WINDOW_MS,
  browserSchedulerDeps,
  createSoundScheduler,
  type SoundScheduler,
} from '../../lib/sound/soundScheduler';
import { NotificationRegion } from './NotificationRegion';

/** What "Test sound" can honestly report. */
export type TestSoundResult = 'played' | 'blocked' | 'unsupported' | 'volume-zero';

export interface NotificationCenterValue {
  scope: NotificationScope;
  prefs: SoundPrefs;
  /** Merges the change into the preferences and saves it at once. */
  setPrefs: (patch: Partial<SoundPrefs>) => void;
  engineState: EngineState;
  /** Starts audio. Call only from a click, key press or touch. */
  unlock: () => Promise<EngineState>;
  /** Unlocks (this must be called from a click) and plays one sound, reporting what really happened. */
  testSound: (kind?: SoundKind) => Promise<TestSoundResult>;
  toasts: ToastState;
  dismissToast: (id: string) => void;
  /** Unread notifications of the signed-in restaurant (0 for the platform owner: platform events are not stored). */
  unreadCount: number;
  /** Whether the live connection is up. */
  connected: boolean;
}

const NotificationCenterContext = createContext<NotificationCenterValue | null>(null);

export function useNotificationCenter(): NotificationCenterValue {
  const ctx = useContext(NotificationCenterContext);
  if (!ctx) throw new Error('useNotificationCenter must be used inside NotificationCenterProvider');
  return ctx;
}

const BAR_DISMISSED_KEY = 'kmenu.sound.bar.dismissed';
/** How long after load a still-locked engine waits before the "sounds are blocked" bar appears. */
const BAR_DELAY_MS = 2500;
const BASELINE_ATTEMPTS = 3;
const BASELINE_RETRY_MS = 3000;
const CATCH_UP_MIN_GAP_MS = 2000;
const SESSION_CHECK_MIN_MS = 30_000;
const STATUS_TOAST_MS = 12_000;
/** Live messages closer together than this share one refresh of the lists. */
const REFRESH_WINDOW_MS = 500;

const subscribeEngine = (onChange: () => void) => soundEngine.subscribe(() => onChange());

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Owns the live notifications of the signed-in person: the subscription, de-duplication, toasts, the
 * unread count and every decision about sound. Mounted once, inside the auth and query providers.
 * Diners and anonymous visitors get nothing from it (their order page has its own small control).
 */
export function NotificationCenterProvider({ children }: { children: ReactNode }) {
  const { user, refreshMe } = useAuth();
  const queryClient = useQueryClient();

  const scope = notificationScope(user);
  const userId = user?.id ?? null;
  const restaurantId = user?.restaurantId ?? null;
  const role = user?.role ?? null;
  // Staff of a restaurant and the platform owner: people for whom this tab is a work tool.
  const audience = user !== null && (scope === 'platform' || isRestaurantStaff(user));

  /* ---------- preferences ---------- */

  const storageKey = userId === null ? null : prefsKey(userId);
  const [prefState, setPrefState] = useState<{ key: string | null; prefs: SoundPrefs }>(() => ({
    key: storageKey,
    prefs: storageKey ? loadPrefs(safeStorage('local'), storageKey) : DEFAULT_PREFS,
  }));
  if (prefState.key !== storageKey) {
    // A different person signed in: their record, not the previous person's.
    setPrefState({
      key: storageKey,
      prefs: storageKey ? loadPrefs(safeStorage('local'), storageKey) : DEFAULT_PREFS,
    });
  }
  const prefs = prefState.key === storageKey ? prefState.prefs : DEFAULT_PREFS;
  const prefsRef = useRef(prefs);
  prefsRef.current = prefs;

  const setPrefs = useCallback(
    (patch: Partial<SoundPrefs>) => {
      if (!storageKey) return;
      const next = sanitizePrefs({ ...prefsRef.current, ...patch });
      prefsRef.current = next;
      setPrefState({ key: storageKey, prefs: next });
      savePrefs(safeStorage('local'), storageKey, next);
    },
    [storageKey],
  );

  // Another tab changed the same record: follow it.
  useEffect(() => {
    if (!storageKey) return;
    const onStorage = (e: StorageEvent) => {
      if (e.key === storageKey) setPrefState({ key: storageKey, prefs: loadPrefs(safeStorage('local'), storageKey) });
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [storageKey]);

  /* ---------- audio ---------- */

  const engineState = useSyncExternalStore(subscribeEngine, soundEngine.getState, (): EngineState => 'locked');
  const [barDismissed, setBarDismissed] = useState(() => safeStorage('session')?.getItem(BAR_DISMISSED_KEY) === '1');
  const [barDelayed, setBarDelayed] = useState(false);
  const [blockedSound, setBlockedSound] = useState(false);

  const schedulerRef = useRef<SoundScheduler | null>(null);
  useEffect(() => {
    const base = browserSchedulerDeps(
        // The volume asked for at enqueue time is ignored: what the person has set NOW is what plays.
        (kind) => soundEngine.play(kind, prefsRef.current.volume, prefsRef.current.theme),
        () => {
          // A sound was due and the browser refused it: that is the one case where the bar returns.
          setBlockedSound(true);
          setBarDismissed(false);
          try {
            safeStorage('session')?.setItem(BAR_DISMISSED_KEY, '0');
          } catch {
            // ignore
          }
        },
      );
    const scheduler = createSoundScheduler({
      ...base,
      // Muted, or the category switched off, while the sound waited: it must not play after all.
      allow: (request) => request.group === undefined || audible(prefsRef.current, request.group),
      // A background tab's timers are throttled, so a free slot starts at once there.
      collapseWindowMs: () => (document.visibilityState === 'hidden' ? 0 : COLLAPSE_WINDOW_MS),
    });
    schedulerRef.current = scheduler;
    return () => {
      scheduler.clear();
      schedulerRef.current = null;
    };
  }, []);

  // Reminders for unanswered waiter calls, bill requests and new orders. Each one goes through `maybeSound`,
  // so mute, per-event switches, the cross-tab claim and the scheduler's no-overlap rule all still apply.
  const remindersRef = useRef<ReminderLoop | null>(null);
  const maybeSoundRef = useRef<((rule: EventRule, key: string) => void) | null>(null);
  const restaurantIdRef = useRef<number | null>(null);
  restaurantIdRef.current = restaurantId;
  useEffect(() => {
    const loop = createReminderLoop({
      now: () => Date.now(),
      setTimer: (fn, ms) => setTimeout(fn, ms),
      clearTimer: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
      intervalSec: () => prefsRef.current.reminderSec,
      // One sound per reminder moment, however many requests are open: the most important one.
      // The claim key is the same in every tab of this browser (a time bucket), so only one of them plays.
      remind: (due) => {
        const bucket = Math.floor(Date.now() / Math.max(1000, prefsRef.current.reminderSec * 1000));
        maybeSoundRef.current?.(due[0].rule, `r${restaurantIdRef.current}:remind:${bucket}`);
      },
    });
    remindersRef.current = loop;
    return () => {
      loop.clear();
      remindersRef.current = null;
    };
  }, []);

  // The person's first click, key press or touch unlocks audio. Nothing is attempted before that.
  const wantsSound = audience && prefs.master;
  useEffect(() => {
    if (!wantsSound || engineState !== 'locked') return;
    return attachGestureUnlock(document, soundEngine);
  }, [wantsSound, engineState]);

  // The bar waits a moment after load: most people click something first and never see it.
  useEffect(() => {
    if (!wantsSound) {
      setBarDelayed(false);
      return;
    }
    const t = setTimeout(() => setBarDelayed(true), BAR_DELAY_MS);
    return () => clearTimeout(t);
  }, [wantsSound]);

  const unlock = useCallback(() => soundEngine.unlock(), []);

  const testSound = useCallback(async (kind: SoundKind = 'NEW_ORDER'): Promise<TestSoundResult> => {
    // unlock() does its work synchronously, so this is still inside the click that called us.
    const state = await soundEngine.unlock();
    if (state === 'unsupported') return 'unsupported';
    if (state !== 'ready') return 'blocked';
    if (prefsRef.current.volume <= 0) return 'volume-zero';
    return soundEngine.play(kind, prefsRef.current.volume, prefsRef.current.theme) ? 'played' : 'blocked';
  }, []);

  const enableFromBar = useCallback(async () => {
    const state = await soundEngine.unlock();
    if (state === 'ready') {
      setBlockedSound(false);
      soundEngine.play('SUCCESS', prefsRef.current.volume, prefsRef.current.theme);
    }
  }, []);

  const dismissBar = useCallback(() => {
    setBarDismissed(true);
    setBlockedSound(false);
    try {
      safeStorage('session')?.setItem(BAR_DISMISSED_KEY, '1');
    } catch {
      // The bar then simply comes back on the next page load.
    }
  }, []);

  const showBar = wantsSound && engineState === 'locked' && !barDismissed && (barDelayed || blockedSound);

  // Muting, or dragging the volume to 0, silences what is already sounding and what is waiting; a new
  // volume applies to the sound that is playing now.
  const silenced = !prefs.master || prefs.volume <= 0;
  useEffect(() => {
    if (silenced) {
      remindersRef.current?.clear();
      schedulerRef.current?.clear();
      soundEngine.stopAll();
    } else {
      soundEngine.setVolume(prefs.volume);
    }
  }, [silenced, prefs.volume]);

  /* ---------- toasts ---------- */

  const [toasts, setToasts] = useState<ToastState>(EMPTY_TOASTS);
  const toastSeq = useRef(0);
  const showToast = useCallback((input: ToastInput) => {
    toastSeq.current += 1;
    const id = `n${toastSeq.current}`;
    setToasts((state) => pushToast(state, input, id));
  }, []);
  const dismiss = useCallback((id: string) => setToasts((state) => dismissToast(state, id)), []);

  /* ---------- per-session state ---------- */

  const session = useRef({
    baseline: null as number | null,
    seen: createSeenIds<number>(),
    seenEvents: createSeenIds<string>(),
    decisions: createDecisionDeduper(),
    lastCatchUp: 0,
  });
  const roleRef = useRef(role);
  roleRef.current = role;

  const resetSession = useCallback(() => {
    const s = session.current;
    s.baseline = null;
    s.seen.clear();
    s.seenEvents.clear();
    s.decisions.clear();
    s.lastCatchUp = 0;
    schedulerRef.current?.clear();
    remindersRef.current?.clear();
  }, []);

  // Everything remembered about one person is dropped when they sign out or someone else signs in.
  useEffect(() => {
    resetSession();
    return () => {
      resetSession();
      setToasts(EMPTY_TOASTS);
    };
  }, [userId, resetSession]);

  /**
   * `key` names the event across tabs: of several tabs of this browser that hear it, one plays the sound.
   * A tab whose audio is still blocked does not take part (it must not steal the claim from a tab that can
   * play); its own attempt fails and shows the "blocked" bar, as before.
   */
  const maybeSound = useCallback((rule: EventRule, key: string) => {
    if (rule.silent || rule.sound === null) return;
    const current = prefsRef.current;
    if (!audible(current, rule.group)) return;
    const request = { kind: rule.sound, volume: current.volume, priority: rule.priority, group: rule.group };
    if (soundEngine.getState() !== 'ready') {
      // After a long idle spell the browser may have suspended the audio context. A page that has had a
      // user gesture is usually allowed to resume it without another one, so try that before giving up;
      // if it still cannot run, the scheduler's play fails and the "sounds are blocked" bar returns.
      void soundEngine.resume().then(() => schedulerRef.current?.enqueue(request));
      return;
    }
    void soundClaims.claim(key).then((won) => {
      if (won) schedulerRef.current?.enqueue(request);
    });
  }, []);

  maybeSoundRef.current = maybeSound;

  // A burst of events refreshes the lists once (plus once more when it ends), not once per event.
  const invalidators = useRef(new Map<string, ReturnType<typeof createCoalescer>>());
  useEffect(() => {
    const map = invalidators.current;
    return () => {
      map.forEach((c) => c.cancel());
      map.clear();
    };
  }, []);
  const invalidate = useCallback(
    (keys: readonly (readonly string[])[]) => {
      // One coalescer per distinct set of keys (the restaurant set and the platform set).
      const name = keys.map((k) => k.join('/')).join('|');
      let coalescer = invalidators.current.get(name);
      if (!coalescer) {
        coalescer = createCoalescer(
          () => {
            for (const queryKey of keys) void queryClient.invalidateQueries({ queryKey: [...queryKey] });
          },
          REFRESH_WINDOW_MS,
          { now: () => Date.now(), setTimer: (f, ms) => setTimeout(f, ms), clearTimer: (h) => clearTimeout(h as ReturnType<typeof setTimeout>) },
        );
        invalidators.current.set(name, coalescer);
      }
      coalescer.trigger();
    },
    [queryClient],
  );

  // When the live connection drops, ask the server whether the session is still good. An expired or revoked
  // token makes the socket fail on every reconnect, and an idle dashboard makes no other request that would
  // notice: this call logs the person out (401) or shows the status screen (suspended) instead of leaving a
  // dashboard that looks live and hears nothing. At most once every SESSION_CHECK_MIN_MS.
  const refreshMeRef = useRef(refreshMe);
  refreshMeRef.current = refreshMe;
  const lastSessionCheck = useRef(0);
  const verifySession = useCallback(() => {
    const now = Date.now();
    if (now - lastSessionCheck.current < SESSION_CHECK_MIN_MS) return;
    lastSessionCheck.current = now;
    void refreshMeRef.current().catch(() => undefined);
  }, []);

  /* ---------- the restaurant topic ---------- */

  const [connected, setConnected] = useState(false);
  const catchUpRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    if (scope !== 'restaurant' || restaurantId === null || userId === null) return;
    const ownId = restaurantId;
    let cancelled = false;
    let unsubscribe: (() => void) | null = null;
    const state = session.current;

    const fetchUnread = (): Promise<AppNotification[]> =>
      queryClient.fetchQuery({
        queryKey: ['notifications', ownId],
        queryFn: () => notificationApi.unread(ownId),
        staleTime: 0,
      });

    async function takeBaseline(): Promise<number> {
      for (let attempt = 1; attempt <= BASELINE_ATTEMPTS; attempt++) {
        try {
          return baselineFrom(await fetchUnread());
        } catch {
          if (cancelled || attempt === BASELINE_ATTEMPTS) break;
          await sleep(BASELINE_RETRY_MS);
          if (cancelled) break;
        }
      }
      // The list could not be read. The socket never replays history, so every message it delivers
      // is genuinely new; with no baseline, ids are told apart by the seen set alone.
      return 0;
    }

    async function catchUp() {
      const now = Date.now();
      if (now - state.lastCatchUp < CATCH_UP_MIN_GAP_MS || state.baseline === null) return;
      state.lastCatchUp = now;
      try {
        const unread = await fetchUnread();
        if (cancelled) return;
        const summary = planCatchUp(unread, state.baseline ?? 0, state.seen, Date.now());
        if (summary) {
          // An order that arrived in a short gap (the connection starting up, a network blip) still
          // deserves the chime; anything older than that is history and stays silent.
          if (summary.recentOrderId !== null && roleHears(roleRef.current, 'ORDER_PLACED')) {
            maybeSound(ruleFor('ORDER_PLACED'), `r${ownId}:n${summary.recentOrderId}`);
          } else if (
            summary.recentWaiterCallId !== null &&
            roleHears(roleRef.current, 'SERVICE_REQUEST_CREATED', { eventKind: 'WAITER_CALL' })
          ) {
            maybeSound(
              ruleForEvent('SERVICE_REQUEST_CREATED', { eventKind: 'WAITER_CALL' }),
              `r${ownId}:n${summary.recentWaiterCallId}`,
            );
          }
          showToast({
            key: 'catch-up',
            tone: 'info',
            title: summary.text,
            body: null,
            lines: summary.newest,
            action: null,
            ttlMs: 15_000,
          });
          invalidate(RESTAURANT_QUERY_KEYS);
        }
      } catch {
        // Offline again; the next reconnect or return to the tab tries once more.
      }
    }
    catchUpRef.current = () => void catchUp();

    function onMessage(payload: unknown) {
      if (cancelled || !isRealtimeMessage(payload)) return;
      const message: RealtimeMessage = payload;
      const plan = planLiveMessage(message, ownId, state.baseline, state.seen, roleRef.current);
      if (plan.verdict !== 'live') return;

      invalidate(RESTAURANT_QUERY_KEYS);
      const rule = plan.rule;
      // A decision about the restaurant may already have been announced from /me (or the other way round).
      if (RESTAURANT_STATUS_TYPES.includes(message.notificationType)) {
        if (!state.decisions.claim(message.notificationType, Date.now())) return;
      }
      showToast(toastForMessage(message, rule.label, rule.tone, roleRef.current ?? 'CUSTOMER'));
      // Everyone sees the toast; only the roles an event concerns hear it (and get reminded about it).
      if (!plan.hears) return;
      maybeSound(rule, plan.key);
    }

    async function start() {
      const baseline = await takeBaseline();
      // A superseded run (sign-out, another account) must not overwrite what the new one is building.
      if (cancelled) return;
      state.baseline = baseline;
      // Loaded on demand so diners never download the websocket client.
      const realtime = await import('../../lib/realtime');
      if (cancelled) return;
      let wasConnected = false;
      let statusCalls = 0;
      unsubscribe = realtime.subscribeTopic(realtime.restaurantTopic(ownId), {
        onMessage,
        onStatus: (isConnected) => {
          if (cancelled) return;
          setConnected(isConnected);
          // The first call only reports the state at subscribe time; later "down" calls are real drops.
          if (!isConnected && statusCalls > 0) verifySession();
          statusCalls += 1;
          // Every (re)connection: anything that arrived while we could not hear it becomes one notice.
          if (isConnected && !wasConnected) void catchUp();
          wasConnected = isConnected;
        },
      });
    }
    void start();

    return () => {
      cancelled = true;
      catchUpRef.current = null;
      unsubscribe?.();
      setConnected(false);
    };
  }, [scope, restaurantId, userId, queryClient, invalidate, showToast, maybeSound, verifySession]);

  /* ---------- reminders: what the server says is still unanswered ---------- */

  // Read again on load, after every live message (the query keys are invalidated), on reconnect and every
  // 30 seconds, so an acknowledgement made on another device stops the reminder too. A failed read keeps
  // the previous answer rather than pretending nothing is pending.
  const remindRequests =
    role !== null &&
    (roleHears(role, 'SERVICE_REQUEST_CREATED', { eventKind: 'WAITER_CALL' }) ||
      roleHears(role, 'SERVICE_REQUEST_CREATED', { eventKind: 'BILL_REQUEST' }));
  const remindOrders = role !== null && roleHears(role, 'ORDER_PLACED');
  const reminderOn = scope === 'restaurant' && restaurantId !== null && prefs.master && prefs.reminderSec > 0;
  const pendingRequests = useQuery({
    queryKey: ['reminder-sync', 'requests', restaurantId],
    queryFn: serviceRequestApi.board,
    enabled: reminderOn && remindRequests,
    refetchInterval: 30_000,
    // A kitchen tablet may sit in a background tab; its reminders must still follow the server.
    refetchIntervalInBackground: true,
    staleTime: 0,
  });
  const pendingOrders = useQuery({
    queryKey: ['reminder-sync', 'orders', restaurantId],
    queryFn: kitchenApi.active,
    enabled: reminderOn && remindOrders,
    refetchInterval: 30_000,
    refetchIntervalInBackground: true,
    staleTime: 0,
  });
  const requestData = pendingRequests.data;
  const orderData = pendingOrders.data;
  // Re-sync after every successful read, even an unchanged one: the age limit moves with the clock.
  const readAt = Math.max(pendingRequests.dataUpdatedAt, pendingOrders.dataUpdatedAt);
  useEffect(() => {
    const loop = remindersRef.current;
    if (!loop) return;
    if (!reminderOn) {
      loop.clear();
      return;
    }
    loop.sync(
      reminderTargets(
        role,
        remindRequests ? (requestData ?? []) : [],
        remindOrders ? (orderData ?? []).map((o) => ({ orderId: o.orderId, status: o.status, createdAt: o.createdAt })) : [],
        Date.now(),
      ),
    );
  }, [reminderOn, role, remindRequests, remindOrders, requestData, orderData, readAt]);

  /* ---------- the platform topic ---------- */

  useEffect(() => {
    if (scope !== 'platform' || userId === null) return;
    let cancelled = false;
    let unsubscribe: (() => void) | null = null;
    const state = session.current;

    function onEvent(payload: unknown) {
      if (cancelled || !isPlatformEvent(payload)) return;
      const event: PlatformRealtimeEvent = payload;
      if (decidePlatformEvent(event, state.seenEvents) !== 'live') return;
      invalidate(PLATFORM_QUERY_KEYS);
      const rule = ruleFor(event.type);
      showToast({
        key: `platform:${event.eventId}`,
        tone: rule.tone,
        title: event.title || rule.label,
        body: event.message || null,
        action: { label: 'Review', to: `/platform-admin/restaurants/${event.restaurantId}` },
        ttlMs: DEFAULT_TOAST_MS * 2,
      });
      maybeSound(rule, `p:${event.eventId}`);
    }

    void import('../../lib/realtime').then((realtime) => {
      if (cancelled) return;
      let wasConnected = false;
      let statusCalls = 0;
      unsubscribe = realtime.subscribeTopic(realtime.PLATFORM_TOPIC, {
        onMessage: onEvent,
        onStatus: (isConnected) => {
          if (cancelled) return;
          setConnected(isConnected);
          if (!isConnected && statusCalls > 0) verifySession();
          statusCalls += 1;
          // Platform events are not stored, so a reconnect can only refresh the lists, silently.
          if (isConnected && !wasConnected) invalidate(PLATFORM_QUERY_KEYS);
          wasConnected = isConnected;
        },
      });
    });

    return () => {
      cancelled = true;
      unsubscribe?.();
      setConnected(false);
    };
  }, [scope, userId, invalidate, showToast, maybeSound, verifySession]);

  /* ---------- coming back to the tab ---------- */

  useEffect(() => {
    if (!audience) return;
    const onVisible = () => {
      if (document.visibilityState !== 'visible') return;
      void soundEngine.resume();
      catchUpRef.current?.();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [audience]);

  /* ---------- restaurant status changes seen through /me ---------- */

  // A restaurant that is not approved cannot subscribe, so the owner learns of a decision by /me changing.
  const watched = user !== null && isRestaurantStaff(user) ? user : null;
  const watchedId = watched?.id ?? null;
  const watchedStatus = watched?.restaurantStatus ?? null;
  const watchedReason = watched?.restaurantStatusReason ?? null;
  const previousStatus = useRef<{ userId: number; status: typeof watchedStatus } | null>(null);
  useEffect(() => {
    if (watchedId === null) {
      previousStatus.current = null;
      return;
    }
    const previous = previousStatus.current;
    previousStatus.current = { userId: watchedId, status: watchedStatus };
    if (!previous || previous.userId !== watchedId) return; // first sight of this person's status
    const type = detectStatusTransition(previous.status, watchedStatus);
    if (!type || !session.current.decisions.claim(type, Date.now())) return;
    const rule = ruleFor(type);
    const copy = statusEventCopy(type, watchedReason);
    showToast({
      key: `status:${type}`,
      tone: rule.tone,
      title: copy.title,
      body: copy.message,
      action: null,
      ttlMs: STATUS_TOAST_MS,
    });
    maybeSound(rule, `r${restaurantId ?? 'none'}:status:${type}`);
  }, [watchedId, watchedStatus, watchedReason, restaurantId, showToast, maybeSound]);

  /* ---------- unread count ---------- */

  const unread = useQuery({
    queryKey: ['notifications', restaurantId],
    queryFn: () => notificationApi.unread(restaurantId!),
    enabled: scope === 'restaurant' && restaurantId !== null,
    staleTime: 15_000,
  });
  const unreadCount = scope === 'restaurant' ? (unread.data?.length ?? 0) : 0;

  const value = useMemo<NotificationCenterValue>(
    () => ({
      scope,
      prefs,
      setPrefs,
      engineState,
      unlock,
      testSound,
      toasts,
      dismissToast: dismiss,
      unreadCount,
      connected,
    }),
    [scope, prefs, setPrefs, engineState, unlock, testSound, toasts, dismiss, unreadCount, connected],
  );

  return (
    <NotificationCenterContext.Provider value={value}>
      {children}
      <NotificationRegion
        toasts={toasts}
        onDismiss={dismiss}
        bar={showBar ? { onEnable: () => void enableFromBar(), onDismiss: dismissBar } : null}
      />
    </NotificationCenterContext.Provider>
  );
}
