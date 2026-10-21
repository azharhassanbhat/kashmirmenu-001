import { useCallback, useEffect, useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { publicApi } from '../../api';
import { ApiError } from '../../api/client';
import type { Cart, CartItem, DinerMe, MenuItem, Order } from '../../api/types';
import { clearStoredDiner, getStoredDiner } from '../../lib/dinerSession';
import { useDinerSession } from './useDinerSession';
import { rememberOrder } from '../../lib/orderHistory';

export interface CartLine {
  /** The line to step up or down, or null when the dish is on several lines or has options. */
  cartItemId: number | null;
  /** Total across every line of the dish. */
  quantity: number;
}

/**
 * Everything the diner screens need for one table: branding, menu, the guest's own cart and the
 * mutations on it. Prices and totals always come from the server's cart response; the browser
 * only sends menu item ids and quantities.
 *
 * The cart belongs to one guest. `diner.data` is that guest once they have entered a name, and
 * null until then; the cart is not requested before it exists.
 */
export function useTableOrdering(qr: string) {
  const queryClient = useQueryClient();
  const cartKey = ['public-cart', qr];

  const table = useQuery({ queryKey: ['public-table', qr], queryFn: () => publicApi.table(qr), retry: false });
  const menu = useQuery({
    queryKey: ['public-menu', qr],
    queryFn: () => publicApi.menu(qr),
    enabled: table.isSuccess,
    refetchInterval: 60_000,
  });
  // Who this device is at this table; null until they have entered a name.
  const { diner, join } = useDinerSession(qr, table.isSuccess);

  const cart = useQuery({
    queryKey: cartKey,
    queryFn: () => publicApi.cart(qr),
    enabled: table.isSuccess && Boolean(diner.data),
    retry: false,
    refetchInterval: 15_000,
  });

  // The table session ended while the guest had the page open: forget the token, ask for a name again.
  const cartError = cart.error;
  useEffect(() => {
    if (cartError instanceof ApiError && cartError.status === 404 && getStoredDiner(qr)) {
      clearStoredDiner(qr);
      queryClient.setQueryData<DinerMe | null>(['public-diner', qr], null);
    }
  }, [cartError, qr, queryClient]);

  const setCart = (data: Cart) => queryClient.setQueryData(cartKey, data);
  const refreshCart = () => queryClient.invalidateQueries({ queryKey: cartKey });

  const add = useMutation({
    mutationFn: (v: { id: number; quantity: number; optionIds?: number[] }) =>
      publicApi.addToCart(qr, v.id, v.quantity, v.optionIds ?? []),
    onSuccess: setCart,
  });
  const setQty = useMutation({
    mutationFn: (v: { cartItemId: number; quantity: number }) => publicApi.setQuantity(qr, v.cartItemId, v.quantity),
    onSuccess: setCart,
  });
  const remove = useMutation({
    mutationFn: (cartItemId: number) => publicApi.removeFromCart(qr, cartItemId),
    onSuccess: refreshCart,
  });
  // Applying a code never consumes it: the server only claims the coupon when the order is placed.
  const applyCoupon = useMutation({
    mutationFn: (couponCode: string) => publicApi.applyCoupon(qr, couponCode),
    onSuccess: setCart,
  });
  const removeCoupon = useMutation({
    mutationFn: () => publicApi.removeCoupon(qr),
    onSuccess: setCart,
  });
  const place = useMutation({
    mutationFn: (notes: string) => publicApi.placeOrder(qr, notes.trim()),
    onSuccess: (order: Order) => {
      rememberOrder(qr, order.orderNumber, order.publicToken);
      queryClient.removeQueries({ queryKey: cartKey });
    },
  });

  const cartByMenuItem = useMemo(() => {
    const map = new Map<number, CartLine>();
    // A dish can now sit on several lines (one per combination of options). The map holds the TOTAL
    // quantity, and a line id only while there is exactly one line without options: that is the
    // only case where a +/- stepper on the dish card can mean one particular line.
    const lines = new Map<number, CartItem[]>();
    cart.data?.items.forEach((i) => lines.set(i.menuItemId, [...(lines.get(i.menuItemId) ?? []), i]));
    lines.forEach((group, menuItemId) => {
      const total = group.reduce((n, i) => n + i.quantity, 0);
      const single = group.length === 1 && (group[0].options?.length ?? 0) === 0 ? group[0] : null;
      map.set(menuItemId, { cartItemId: single ? single.id : null, quantity: total });
    });
    return map;
  }, [cart.data]);

  const menuItemsById = useMemo(() => {
    const map = new Map<number, MenuItem>();
    menu.data?.forEach((c) => c.items.forEach((i) => map.set(i.id, i)));
    return map;
  }, [menu.data]);

  // The card whose request is in flight, so only that card shows a working state.
  let pendingMenuItemId: number | null = null;
  if (add.isPending && add.variables) pendingMenuItemId = add.variables.id;
  else if (setQty.isPending && setQty.variables)
    pendingMenuItemId = cart.data?.items.find((i) => i.id === setQty.variables!.cartItemId)?.menuItemId ?? null;
  else if (remove.isPending && remove.variables !== undefined)
    pendingMenuItemId = cart.data?.items.find((i) => i.id === remove.variables)?.menuItemId ?? null;

  const itemCount = cart.data?.items.reduce((n, i) => n + i.quantity, 0) ?? 0;

  /** Sets a cart line to an absolute quantity; zero or less removes it. */
  // `mutate` is referentially stable, so these callbacks are too (lets memoised cards skip renders).
  const removeMutate = remove.mutate;
  const setQtyMutate = setQty.mutate;
  const changeQuantity = useCallback(
    (cartItemId: number, quantity: number) =>
      quantity <= 0 ? removeMutate(cartItemId) : setQtyMutate({ cartItemId, quantity }),
    [removeMutate, setQtyMutate],
  );
  const removeLine = useCallback((cartItemId: number) => removeMutate(cartItemId), [removeMutate]);

  return {
    table,
    menu,
    diner,
    join,
    cart,
    add,
    place,
    applyCoupon,
    removeCoupon,
    changeQuantity,
    removeLine,
    cartByMenuItem,
    menuItemsById,
    pendingMenuItemId,
    itemCount,
    busy: add.isPending || setQty.isPending || remove.isPending,
    cartError: add.error ?? setQty.error ?? remove.error,
  };
}
