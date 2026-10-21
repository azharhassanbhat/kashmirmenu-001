import { http, requestBlob } from './client';
import { buildListQuery, type PlatformListParams } from '../lib/platform';
import type {
  PublicReviews,
  AnalyticsFilters,
  AnalyticsOverview,
  AppNotification,
  AuthResponse,
  BillingSettings,
  Cart,
  Category,
  CategoryInput,
  Coupon,
  CouponInput,
  DashboardSummary,
  DinerBill,
  DinerJoin,
  DinerMe,
  Inventory,
  KitchenOrder,
  Me,
  MenuItem,
  OptionGroup,
  OptionGroupInput,
  MenuImportPreview,
  MenuImportResult,
  MenuItemInput,
  OnboardingProgress,
  Order,
  FeedbackEntry,
  FeedbackSaved,
  FeedbackSummary,
  OrderHistoryEntry,
  Receipt,
  OrderPaymentSummary,
  OrderStatus,
  OrderTracking,
  PageResponse,
  PlatformOverview,
  PlatformRestaurantDetail,
  PlatformRestaurantSummary,
  Payment,
  PaymentMeta,
  PaymentMethod,
  PaymentSettings,
  PaymentSettingsInput,
  PaymentTransaction,
  PaymentTransactionDetail,
  RoleName,
  Reconciliation,
  PublicMenuCategory,
  PublicServiceRequest,
  PublicTable,
  PublicLanding,
  WifiDetails,
  AdminLanding,
  AdminLandingInput,
  RewardsInfo,
  RewardClaim,
  RewardRedeem,
  RewardMember,
  QrCards,
  UploadedImage,
  StaffMember,
  Restaurant,
  RestaurantTable,
  Settlement,
  SettlementKind,
  ServiceRequestStatus,
  ServiceRequestType,
  SettlementStaff,
  StaffServiceRequest,
  TableBill,
  TableInput,
  TableSessionDetail,
  TableSessionSummary,
} from './types';

const ALL = 'page=0&size=500&sortBy=id&direction=asc';

export const authApi = {
  /** Revokes this user's tokens server-side. Best effort: the client clears its copy regardless. */
  logout: () => http.post<void>('/api/v1/auth/logout'),
  login: (email: string, password: string) =>
    http.post<AuthResponse>('/api/v1/auth/login', { email, password }),
  registerOwner: (body: {
    restaurantName: string;
    restaurantEmail?: string;
    restaurantPhone?: string;
    ownerName: string;
    ownerEmail: string;
    password: string;
  }) => http.post<AuthResponse>('/api/v1/auth/register-owner', body),
  me: () => http.get<Me>('/api/v1/me'),
};

export const publicApi = {
  table: (qr: string) => http.get<PublicTable>(`/api/public/tables/${qr}`),
  menu: (qr: string) => http.get<PublicMenuCategory[]>(`/api/public/tables/${qr}/menu`),
  reviews: (qr: string) => http.get<PublicReviews>(`/api/public/tables/${qr}/reviews`),
  /** Joins the table with a name; the response carries this guest's token (shown once). */
  join: (qr: string, name: string) => http.post<DinerJoin>(`/api/public/tables/${qr}/join`, { name }),
  /** Who the stored token belongs to; 404 once the table session has ended. */
  me: (qr: string) => http.get<DinerMe>(`/api/public/tables/${qr}/me`),
  cart: (qr: string) => http.get<Cart>(`/api/public/tables/${qr}/cart`),
  addToCart: (qr: string, menuItemId: number, quantity: number, optionIds: number[] = []) =>
    http.post<Cart>(`/api/public/tables/${qr}/cart/items`, { menuItemId, quantity, optionIds }),
  setQuantity: (qr: string, cartItemId: number, quantity: number) =>
    http.put<Cart>(`/api/public/tables/${qr}/cart/items/${cartItemId}`, { quantity }),
  applyCoupon: (qr: string, couponCode: string) =>
    http.post<Cart>(`/api/public/tables/${qr}/cart/coupon`, { couponCode }),
  removeCoupon: (qr: string) => http.delete<Cart>(`/api/public/tables/${qr}/cart/coupon`),
  removeFromCart: (qr: string, cartItemId: number) =>
    http.delete(`/api/public/tables/${qr}/cart/items/${cartItemId}`),
  placeOrder: (qr: string, notes: string) =>
    http.post<Order>(`/api/public/tables/${qr}/orders`, { notes: notes || null }),
  /** Keyed by the order's public token: order numbers are sequential, this endpoint is public. */
  track: (token: string) => http.get<OrderTracking>(`/api/public/orders/by-token/${token}`),
  /** Rate a served order. Sending again replaces the earlier rating. Private to the restaurant's management. */
  /** A printable receipt for the guest's own order (not available once cancelled). */
  receipt: (token: string) => http.get<Receipt>(`/api/public/orders/by-token/${token}/receipt`),
  sendFeedback: (token: string, rating: number, comment: string) =>
    http.put<FeedbackSaved>(`/api/public/orders/by-token/${token}/feedback`, { rating, comment }),
  pay: (token: string, paymentMethod: PaymentMethod) =>
    http.post<Payment>(`/api/public/orders/by-token/${token}/payments`, { paymentMethod }),

  /** "My bill": the calling guest's own orders and what they still owe. */
  bill: (qr: string) => http.get<DinerBill>(`/api/public/tables/${qr}/bill`),
  /** The whole visit by guest: names and amounts only. */
  tableBill: (qr: string) => http.get<TableBill>(`/api/public/tables/${qr}/bill/table`),
  /**
   * Starts paying. The Idempotency-Key makes a retry of the SAME attempt safe: if the first request's
   * answer was lost, sending it again returns the settlement already made instead of charging twice.
   */
  settle: (qr: string, kind: SettlementKind, paymentMethod: PaymentMethod, idempotencyKey: string) =>
    http.post<Settlement>(
      `/api/public/tables/${qr}/settlements`,
      { kind, paymentMethod },
      { 'Idempotency-Key': idempotencyKey },
    ),
  /** The guest's own settlements, newest first: how a refreshed page finds a payment in progress. */
  settlements: (qr: string) => http.get<Settlement[]>(`/api/public/tables/${qr}/settlements`),
  /** UPI: "I've completed payment". A claim only; staff still confirm the money. The reference is optional. */
  claimPaid: (qr: string, code: string, reference?: string) =>
    http.post<Settlement>(`/api/public/tables/${qr}/settlements/${code}/claim-paid`, {
      reference: reference && reference.trim() ? reference.trim() : null,
    }),
  cancelSettlement: (qr: string, code: string) =>
    http.post<Settlement>(`/api/public/tables/${qr}/settlements/${code}/cancel`),
};

/** The owner's live view of the floor: open tables, who is at them, what they owe. */
/** Guests asking for something at the table. The diner token is attached by the client for these routes. */
export const publicServiceRequestApi = {
  list: (qr: string) => http.get<PublicServiceRequest[]>(`/api/public/tables/${qr}/service-requests`),
  create: (qr: string, type: ServiceRequestType, note?: string) =>
    http.post<PublicServiceRequest>(`/api/public/tables/${qr}/service-requests`, {
      type,
      note: note?.trim() ? note.trim() : null,
    }),
  cancel: (qr: string, requestNumber: string) =>
    http.post<PublicServiceRequest>(`/api/public/tables/${qr}/service-requests/${requestNumber}/cancel`),
};

/** The staff board. */
export const serviceRequestApi = {
  board: () => http.get<StaffServiceRequest[]>('/api/v1/service-requests'),
  get: (id: number) => http.get<StaffServiceRequest>(`/api/v1/service-requests/${id}`),
  setStatus: (id: number, status: ServiceRequestStatus) =>
    http.put<StaffServiceRequest>(`/api/v1/service-requests/${id}/status`, { status }),
};

export const tableSessionApi = {
  active: () => http.get<TableSessionSummary[]>('/api/v1/table-sessions/active'),
  /** Closed in the last day, newest first, so one closed by mistake can be reopened. */
  recent: () => http.get<TableSessionSummary[]>('/api/v1/table-sessions/recent'),
  detail: (id: number) => http.get<TableSessionDetail>(`/api/v1/table-sessions/${id}`),
  /** Never blocked by money owing; nothing is deleted or settled. */
  close: (id: number) => http.post<TableSessionDetail>(`/api/v1/table-sessions/${id}/close`),
  reopen: (id: number) => http.post<TableSessionDetail>(`/api/v1/table-sessions/${id}/reopen`),
};

/** Guests' payments waiting for staff, and confirming that the money arrived. */
export const settlementApi = {
  pending: () => http.get<SettlementStaff[]>('/api/v1/settlements/pending'),
  setStatus: (id: number, paymentStatus: 'SUCCESS' | 'FAILED' | 'CANCELLED', transactionId?: string) =>
    http.put<SettlementStaff>(`/api/v1/settlements/${id}/status`, {
      paymentStatus,
      transactionId: transactionId && transactionId.trim() ? transactionId.trim() : null,
    }),
};

export const menuApi = {
  list: () => http.get<PageResponse<MenuItem>>(`/api/menu-items?${ALL}`),
  create: (body: MenuItemInput) => http.post<MenuItem>('/api/menu-items', body),
  update: (id: number, body: MenuItemInput) => http.put<MenuItem>(`/api/menu-items/${id}`, body),
  setAvailable: (id: number, available: boolean) =>
    http.patch<MenuItem>(`/api/menu-items/${id}/${available ? 'available' : 'unavailable'}`),
  setActive: (id: number, active: boolean) =>
    http.patch<MenuItem>(`/api/menu-items/${id}/${active ? 'activate' : 'deactivate'}`),
};

/** The restaurant's team: create staff, change roles, switch accounts off, reset passwords. */
export const staffApi = {
  list: () => http.get<StaffMember[]>('/api/v1/staff'),
  create: (body: { name: string; email: string; role: RoleName; password: string }) =>
    http.post<StaffMember>('/api/v1/staff', body),
  update: (id: number, body: { name?: string; role?: RoleName; active?: boolean }) =>
    http.put<StaffMember>(`/api/v1/staff/${id}`, body),
  resetPassword: (id: number, password: string) => http.put<void>(`/api/v1/staff/${id}/password`, { password }),
};

/** The signed-in restaurant's service charge and tax rules (management only). */
export const billingSettingsApi = {
  get: () => http.get<BillingSettings>('/api/v1/billing-settings'),
  save: (settings: BillingSettings) => http.put<BillingSettings>('/api/v1/billing-settings', settings),
};

/** What guests said about their meals: the list (newest first) and the summary. Management only. */
export const feedbackApi = {
  list: (params: { maxRating?: number; page?: number; size?: number }) => {
    const q = new URLSearchParams();
    if (params.maxRating !== undefined) q.set('maxRating', String(params.maxRating));
    q.set('page', String(params.page ?? 0));
    q.set('size', String(params.size ?? 20));
    return http.get<PageResponse<FeedbackEntry>>(`/api/v1/feedback?${q.toString()}`);
  },
  summary: (days?: number) => {
    const query = days ? `?days=${days}` : '';
    return http.get<FeedbackSummary>(`/api/v1/feedback/summary${query}`);
  },
};

/** Uploading a dish photo. The server checks and re-encodes it; what comes back is a public address. */
export const imageApi = {
  upload: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return http.post<UploadedImage>('/api/v1/images', form);
  },
};

/** Loading a whole menu from a CSV file: preview first (changes nothing), then import. */
export const menuImportApi = {
  preview: (csv: string) => http.post<MenuImportPreview>('/api/menu-items/import/preview', { csv }),
  commit: (csv: string) => http.post<MenuImportResult>('/api/menu-items/import', { csv }),
};

/** The "get started" checklist, worked out by the server from what exists. */
export const onboardingApi = {
  get: () => http.get<OnboardingProgress>('/api/v1/onboarding'),
};

/** Portion sizes, add-ons and spice levels for one dish (management only). */
export const menuOptionApi = {
  get: (menuItemId: number) => http.get<OptionGroup[]>(`/api/menu-items/${menuItemId}/options`),
  save: (menuItemId: number, groups: OptionGroupInput[]) =>
    http.put<OptionGroup[]>(`/api/menu-items/${menuItemId}/options`, { groups }),
  setAvailable: (menuItemId: number, optionId: number, available: boolean) =>
    http.put<OptionGroup[]>(`/api/menu-items/${menuItemId}/options/${optionId}/availability`, { available }),
};

export const categoryApi = {
  list: () => http.get<PageResponse<Category>>(`/api/categories?${ALL}`),
  create: (body: CategoryInput) => http.post<Category>('/api/categories', body),
  update: (id: number, body: CategoryInput) => http.put<Category>(`/api/categories/${id}`, body),
  setActive: (id: number, active: boolean) =>
    http.patch<Category>(`/api/categories/${id}/${active ? 'activate' : 'deactivate'}`),
};

export const restaurantApi = {
  get: (id: number) => http.get<Restaurant>(`/api/restaurants/${id}`),
};

export const tableApi = {
  list: () => http.get<RestaurantTable[]>('/api/v1/tables'),
  create: (body: TableInput) => http.post<RestaurantTable>('/api/v1/tables', body),
  update: (id: number, body: TableInput) => http.put<RestaurantTable>(`/api/v1/tables/${id}`, body),
  remove: (id: number) => http.delete(`/api/v1/tables/${id}`),
  setActive: (id: number, active: boolean) =>
    http.patch<void>(`/api/v1/tables/${id}/${active ? 'activate' : 'deactivate'}`),
  /** The QR code as a PNG. `size` (pixels, 200 to 1200) asks for a sharper image for printing big. */
  qr: (id: number, size?: number) => requestBlob(`/api/v1/tables/${id}/qr${size ? `?size=${size}` : ''}`),
  qrCards: () => http.get<QrCards>('/api/v1/tables/qr-cards'),
};

export const inventoryApi = {
  list: () => http.get<Inventory[]>('/api/v1/inventory'),
  create: (body: {
    restaurantId: number;
    menuItemId: number;
    availableQuantity: number;
    minimumQuantity: number;
  }) => http.post<Inventory>('/api/v1/inventory', body),
  updateMinimum: (id: number, minimumQuantity: number) =>
    http.put<Inventory>(`/api/v1/inventory/${id}`, { minimumQuantity }),
  updateStock: (id: number, availableQuantity: number) =>
    http.patch<Inventory>(`/api/v1/inventory/${id}/stock`, { availableQuantity }),
  remove: (id: number) => http.delete(`/api/v1/inventory/${id}`),
};

export const couponApi = {
  list: (restaurantId: number) => http.get<Coupon[]>(`/api/v1/coupons?restaurantId=${restaurantId}`),
  create: (restaurantId: number, body: CouponInput) =>
    http.post<Coupon>('/api/v1/coupons', { ...body, restaurantId }),
  update: (id: number, body: Omit<CouponInput, 'code'>) =>
    http.put<Coupon>(`/api/v1/coupons/${id}`, body),
  remove: (id: number) => http.delete(`/api/v1/coupons/${id}`),
};

export const paymentSettingsApi = {
  get: (restaurantId: number) =>
    http.get<PaymentSettings>(`/api/v1/restaurants/${restaurantId}/payment-settings`),
  create: (restaurantId: number, body: PaymentSettingsInput) =>
    http.post<PaymentSettings>(`/api/v1/restaurants/${restaurantId}/payment-settings`, body),
  update: (restaurantId: number, body: PaymentSettingsInput) =>
    http.put<PaymentSettings>(`/api/v1/restaurants/${restaurantId}/payment-settings`, body),
};

export const dashboardApi = {
  summary: () => http.get<DashboardSummary>('/api/v1/dashboard/summary'),
};

export const notificationApi = {
  unread: (restaurantId: number) =>
    http.get<AppNotification[]>(`/api/v1/notifications/unread?restaurantId=${restaurantId}`),
  /** Read and unread, newest first (the dashboard's notification panel). */
  recent: (size = 8) => http.get<PageResponse<AppNotification>>(`/api/v1/notifications?page=0&size=${size}`),
  markRead: (id: number) => http.put<AppNotification>(`/api/v1/notifications/${id}/read`, { read: true }),
};

export const orderApi = {
  /** Paged since the endpoint stopped returning every order ever placed. */
  list: (page = 0, size = 20) =>
    http.get<PageResponse<Order>>(`/api/v1/orders?page=${page}&size=${size}`),
  /** `reason` and `note` apply only to CANCELLED. The reason is one of the server's CancelReason codes. */
  setStatus: (orderId: number, status: OrderStatus, reason?: string, note?: string) => {
    const q = new URLSearchParams({ status });
    if (reason) q.set('reason', reason);
    if (note && note.trim()) q.set('note', note.trim());
    return http.put<Order>(`/api/v1/orders/${orderId}/status?${q.toString()}`);
  },
  history: (orderId: number) => http.get<OrderHistoryEntry[]>(`/api/v1/orders/${orderId}/history`),
  receipt: (orderId: number) => http.get<Receipt>(`/api/v1/orders/${orderId}/receipt`),
  payment: (orderId: number) => http.get<Payment>(`/api/v1/payments/orders/${orderId}`),
  /** Everything paid, pending and still due on an order (an order may be split across methods). */
  paymentSummary: (orderId: number) =>
    http.get<OrderPaymentSummary>(`/api/v1/payments/orders/${orderId}/summary`),
  /** Confirms a payment the diner started. The provider is recorded here, not guessed later. */
  setPaymentStatus: (
    paymentId: number,
    body: {
      paymentStatus: 'SUCCESS' | 'FAILED' | 'CANCELLED';
      paymentProvider?: string | null;
      cardType?: string | null;
      transactionId?: string | null;
    },
  ) => http.put<Payment>(`/api/v1/payments/${paymentId}/status`, body),
  /** Records money taken at the counter as one or more components. */
  collect: (
    orderId: number,
    body: {
      payments: {
        paymentMethod: PaymentMethod;
        paymentProvider?: string | null;
        cardType?: string | null;
        amount: number;
        transactionId?: string | null;
      }[];
      idempotencyKey?: string;
    },
  ) => http.post<OrderPaymentSummary>(`/api/v1/payments/orders/${orderId}/collect`, body),
  refund: (paymentId: number, body: { amount: number; reason?: string | null }) =>
    http.post<Payment>(`/api/v1/payments/${paymentId}/refunds`, body),
};

/** Turns the dashboard's filter state into the query string every analytics endpoint shares. */
export function analyticsQuery(f: AnalyticsFilters, extra: Record<string, string | number> = {}): string {
  const params = new URLSearchParams();
  params.set('period', f.period);
  if (f.period === 'CUSTOM') {
    if (f.from) params.set('from', f.from);
    if (f.to) params.set('to', f.to);
  }
  if (f.granularity) params.set('granularity', f.granularity);
  if (f.methods.length) params.set('methods', f.methods.join(','));
  if (f.providers.length) params.set('providers', f.providers.join(','));
  if (f.statuses.length) params.set('statuses', f.statuses.join(','));
  if (f.staffId != null) params.set('staffId', String(f.staffId));
  for (const [key, value] of Object.entries(extra)) {
    if (value !== '' && value != null) params.set(key, String(value));
  }
  return params.toString();
}

const ANALYTICS = '/api/v1/payment-analytics';

export const paymentAnalyticsApi = {
  meta: () => http.get<PaymentMeta>(`${ANALYTICS}/meta`),
  overview: (f: AnalyticsFilters) => http.get<AnalyticsOverview>(`${ANALYTICS}/overview?${analyticsQuery(f)}`),
  transactions: (
    f: AnalyticsFilters,
    opts: { search?: string; page?: number; size?: number; sortBy?: string; direction?: string },
  ) =>
    http.get<PageResponse<PaymentTransaction>>(
      `${ANALYTICS}/transactions?${analyticsQuery(f, {
        search: opts.search ?? '',
        page: opts.page ?? 0,
        size: opts.size ?? 20,
        sortBy: opts.sortBy ?? 'time',
        direction: opts.direction ?? 'desc',
      })}`,
    ),
  transaction: (paymentId: number) =>
    http.get<PaymentTransactionDetail>(`${ANALYTICS}/transactions/${paymentId}`),
  exportCsv: (f: AnalyticsFilters, search?: string) =>
    requestBlob(`${ANALYTICS}/transactions/export?${analyticsQuery(f, { search: search ?? '' })}`),
  reconciliation: (date: string) => http.get<Reconciliation>(`${ANALYTICS}/reconciliation?date=${date}`),
  recordCashCount: (body: { date: string; countedCash: number; note?: string }) =>
    http.post<Reconciliation>(`${ANALYTICS}/reconciliation/counts`, body),
  markSettled: (date: string) => http.post<Reconciliation>(`${ANALYTICS}/reconciliation/settle?date=${date}`),
};

export const kitchenApi = {
  active: () => http.get<KitchenOrder[]>('/api/v1/kitchen/orders/active'),
  /** Today's orders in every status, including served and cancelled. */
  today: () => http.get<KitchenOrder[]>('/api/v1/kitchen/orders/today'),
  advance: (orderId: number, step: 'confirm' | 'preparing' | 'ready' | 'served') =>
    http.put<KitchenOrder>(`/api/v1/kitchen/orders/${orderId}/${step}`),
};

/** The QR landing page, for guests. The table's QR identifier says which restaurant. */
const REWARD_HEADER = 'X-Reward-Token';

export const landingApi = {
  get: (qr: string) => http.get<PublicLanding>(`/api/public/tables/${qr}/landing`),
  wifi: (qr: string) => http.get<WifiDetails>(`/api/public/tables/${qr}/wifi`),
  wifiQr: (qr: string) => requestBlob(`/api/public/tables/${qr}/wifi/qr`),
  rewards: (qr: string, secret: string) =>
    http.get<RewardsInfo>(`/api/public/tables/${qr}/rewards`, { [REWARD_HEADER]: secret }),
  joinRewards: (qr: string, secret: string) =>
    http.post<RewardsInfo>(`/api/public/tables/${qr}/rewards/join`, undefined, { [REWARD_HEADER]: secret }),
  claimRewards: (qr: string, secret: string, orderToken: string) =>
    http.post<RewardClaim>(`/api/public/tables/${qr}/rewards/claim`, { orderToken }, { [REWARD_HEADER]: secret }),
};

/** The owner's side: settings and redeeming a guest's reward. */
export const landingAdminApi = {
  get: () => http.get<AdminLanding>('/api/v1/landing'),
  save: (input: AdminLandingInput) => http.put<AdminLanding>('/api/v1/landing', input),
  member: (code: string) => http.get<RewardMember>(`/api/v1/rewards/members/${encodeURIComponent(code.trim())}`),
  redeem: (memberCode: string) => http.post<RewardRedeem>('/api/v1/rewards/redeem', { memberCode }),
};

/** The platform owner's side: every registered restaurant and the approval decisions. PLATFORM_OWNER only. */
export const platformAdminApi = {
  overview: () => http.get<PlatformOverview>('/api/platform-admin/overview'),
  list: (params: PlatformListParams) =>
    http.get<PageResponse<PlatformRestaurantSummary>>(`/api/platform-admin/restaurants?${buildListQuery(params)}`),
  get: (id: number) => http.get<PlatformRestaurantDetail>(`/api/platform-admin/restaurants/${id}`),
  approve: (id: number) => http.post<PlatformRestaurantDetail>(`/api/platform-admin/restaurants/${id}/approve`),
  reactivate: (id: number) => http.post<PlatformRestaurantDetail>(`/api/platform-admin/restaurants/${id}/reactivate`),
  reject: (id: number, reason: string) =>
    http.post<PlatformRestaurantDetail>(`/api/platform-admin/restaurants/${id}/reject`, { reason }),
  suspend: (id: number, reason: string) =>
    http.post<PlatformRestaurantDetail>(`/api/platform-admin/restaurants/${id}/suspend`, { reason }),
};
