export type OrderStatus =
  | 'PENDING'
  | 'CONFIRMED'
  | 'PREPARING'
  | 'READY'
  | 'SERVED'
  | 'CANCELLED';
/** Open-ended: the server defines the methods and sends them in PaymentMeta. */
export type PaymentMethod = string;
export type PaymentStatus =
  | 'PENDING'
  | 'SUCCESS'
  | 'FAILED'
  | 'CANCELLED'
  | 'PARTIALLY_REFUNDED'
  | 'REFUNDED';
export type PaymentProvider = string;
export type CardType = 'CREDIT' | 'DEBIT' | 'OTHER';
export type DiscountType = 'PERCENTAGE' | 'FIXED';
export type RoleName =
  | 'PLATFORM_OWNER'
  | 'ADMIN'
  | 'RESTAURANT_OWNER'
  | 'MANAGER'
  | 'CHEF'
  | 'WAITER'
  | 'CASHIER'
  | 'CUSTOMER';

export type RestaurantStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED';

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
}

export interface Me {
  id: number;
  name: string;
  email: string;
  role: RoleName;
  restaurantId: number | null;
  /** Approval state of this account's restaurant; null (or absent on an older server) when it has none. */
  restaurantStatus?: RestaurantStatus | null;
  /** The rejection or suspension reason, when there is one. */
  restaurantStatusReason?: string | null;
}

export interface MenuItem {
  id: number;
  name: string;
  description: string | null;
  price: number;
  imageUrl: string | null;
  veg: boolean;
  available: boolean;
  active: boolean;
  displayOrder: number;
  preparationTime: number;
  restaurantId: number;
  categoryId: number;
  categoryName: string;
  /* Stage B. The public menu fills every field below; admin lists leave some undefined. */
  chefPick?: boolean;
  todaysSpecial?: boolean;
  /** True once the restaurant has declared allergens (even "none"); false means it has not said. */
  allergenInfoProvided?: boolean;
  allergens?: Tag[];
  dietaryLabels?: Tag[];
  popular?: boolean;
  optionGroups?: OptionGroup[];
}

export interface Tag {
  code: string;
  label: string;
}

export type OptionKind = 'SINGLE' | 'MULTI';

export interface OptionChoice {
  id: number;
  name: string;
  /** Change from the dish's base price; negative for a smaller portion. */
  priceDelta: number;
  available: boolean;
  defaultOption: boolean;
}

export interface OptionGroup {
  id: number;
  name: string;
  kind: OptionKind;
  required: boolean;
  maxSelect: number | null;
  options: OptionChoice[];
}

/** A choice as copied onto a cart or order line. */
export interface SelectedOption {
  groupName: string;
  optionName: string;
  priceDelta: number;
}

/** Saving a dish's option groups. Ids are echoed back so they stay stable; omit them for new ones. */
export interface OptionGroupInput {
  id?: number;
  name: string;
  kind: OptionKind;
  required: boolean;
  maxSelect?: number | null;
  options: { id?: number; name: string; priceDelta: number; available: boolean; defaultOption: boolean }[];
}

export interface MenuItemInput {
  name: string;
  description?: string;
  price: number;
  imageUrl?: string;
  veg: boolean;
  available: boolean;
  active: boolean;
  displayOrder: number;
  preparationTime: number;
  categoryId: number;
  /** Omit (leave undefined) to keep what is stored; an empty array declares "none". */
  chefPick?: boolean;
  todaysSpecial?: boolean;
  allergens?: string[];
  dietaryLabels?: string[];
}

export interface Category {
  id: number;
  name: string;
  description: string | null;
  displayOrder: number;
  active: boolean;
}

export interface CategoryInput {
  name: string;
  description?: string;
  displayOrder: number;
  active: boolean;
}

export interface Restaurant {
  id: number;
  name: string;
  slug: string;
  logo: string | null;
  primaryColor: string | null;
  secondaryColor: string | null;
}

export interface RestaurantTable {
  id: number;
  tableCode: string;
  tableName: string;
  capacity: number;
  qrIdentifier: string;
  active: boolean;
}

export interface TableInput {
  tableCode: string;
  tableName: string;
  capacity: number;
}

export interface Inventory {
  id: number;
  menuItemId: number;
  menuItemName: string;
  availableQuantity: number;
  minimumQuantity: number;
  inStock: boolean;
}

export interface Coupon {
  id: number;
  code: string;
  name: string;
  description: string | null;
  discountType: DiscountType;
  discountValue: number;
  minimumOrderAmount: number;
  maximumDiscount: number | null;
  /** Fewest dishes the order must contain. 0 for no requirement. A count, not an amount. */
  minimumItems: number;
  usageLimit: number;
  usedCount: number;
  startDate: string;
  expiryDate: string;
  active: boolean;
}

export interface CouponInput {
  code: string;
  name: string;
  description?: string;
  discountType: DiscountType;
  discountValue: number;
  minimumOrderAmount: number;
  maximumDiscount?: number | null;
  /** Fewest dishes the order must contain. 0 for no requirement. A count, not an amount. */
  minimumItems: number;
  usageLimit: number;
  startDate: string;
  expiryDate: string;
  active: boolean;
}

export interface PaymentSettings {
  id: number;
  cashEnabled: boolean;
  upiEnabled: boolean;
  cardEnabled: boolean;
  walletEnabled: boolean;
  upiId: string | null;
  merchantName: string | null;
  /** The owner's uploaded UPI QR photo (one of our own /api/public/images/ addresses), or null. */
  upiQrUrl: string | null;
}

export type PaymentSettingsInput = Omit<PaymentSettings, 'id'>;

export interface DashboardSummary {
  todayRevenue: number;
  todayOrders: number;
  pendingOrders: number;
  preparingOrders: number;
  readyOrders: number;
  servedOrders: number;
  unreadNotifications: number;
}

export interface AppNotification {
  id: number;
  orderId: number | null;
  orderNumber: string | null;
  title: string;
  message: string;
  notificationType: string;
  /** Structured meaning; absent on an older server. */
  eventKind?: string | null;
  read: boolean;
  createdAt: string;
}

export interface OrderItem {
  id: number;
  menuItemId: number;
  itemName: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
  options?: SelectedOption[];
}

export interface Order {
  id: number;
  orderNumber: string;
  /** The guest who placed it; null for orders staff placed themselves. */
  dinerName?: string | null;
  /** Payment state, sent with the row so the list needs no follow-up request per order. */
  paymentStatus?: PaymentStatus | null;
  amountPaid?: number;
  balanceDue?: number;
  /**
   * Key for the public tracking URL. Random, because that endpoint is unauthenticated.
   * Non-null server-side; typed as required so a missing value is a type error rather than a
   * silent navigation to /order/undefined.
   */
  publicToken: string;
  couponCode?: string | null;
  restaurantTableId: number;
  status: OrderStatus;
  subtotal: number;
  tax: number;
  discount: number;
  total: number;
  notes: string | null;
  items: OrderItem[];
}

export interface KitchenOrder {
  orderId: number;
  orderNumber: string;
  restaurantTableId: number;
  tableName: string;
  status: OrderStatus;
  total: number;
  notes: string | null;
  createdAt: string;
  items: OrderItem[];
  /** How long the kitchen is expected to need: the slowest dish's preparation time. */
  prepTargetMinutes?: number;
  /** Minutes open, by the server's clock. */
  elapsedMinutes?: number;
  /** Open longer than the target and not yet ready. Decided by the server. */
  delayed?: boolean;
  overdueMinutes?: number;
}

/** One step in an order's recorded history; `from` is null for the order being placed. */
export interface OrderHistoryEntry {
  from: OrderStatus | null;
  to: OrderStatus;
  actor: string;
  reason: string | null;
  reasonLabel: string | null;
  note: string | null;
  at: string;
}

export interface Payment {
  id: number;
  orderId: number;
  paymentMethod: PaymentMethod;
  paymentProvider: PaymentProvider | null;
  cardType: CardType | null;
  paymentStatus: PaymentStatus;
  transactionId: string | null;
  amount: number;
  refundedAmount: number;
  paidAt: string | null;
  paymentTimestamp: string;
}

/** What an order has paid and still owes, derived from its individual payments. */
export interface OrderPaymentSummary {
  orderId: number;
  orderNumber: string;
  orderTotal: number;
  settled: number;
  pending: number;
  outstanding: number;
  status: PaymentStatus | null;
  payments: Payment[];
}

export interface CartItem {
  id: number;
  menuItemId: number;
  menuItemName: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
  options?: SelectedOption[];
}

export interface Cart {
  id: number;
  status: string;
  subtotal: number;
  tax: number;
  serviceCharge?: number;
  taxIncluded?: number;
  taxName?: string | null;
  taxRate?: number;
  serviceChargeRate?: number;
  pricesIncludeTax?: boolean;
  discount: number;
  total: number;
  items: CartItem[];
  /** The coupon currently applied to this guest's cart, or null. */
  couponCode: string | null;
  couponName: string | null;
}

/** Returned once, when a guest joins a table. The token is their only credential. */
export interface DinerJoin {
  dinerToken: string;
  displayName: string;
  visitId: string;
}

/** Who the stored token belongs to. */
export interface DinerMe {
  displayName: string;
  visitId: string;
}

export type SettlementKind = 'SEPARATE' | 'COMBINED';
export type SettlementStatus = 'PENDING' | 'SUCCESS' | 'FAILED' | 'CANCELLED' | 'PARTIAL';

export interface BillItem {
  name: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
}

/** One of the caller's own orders on their bill. */
export interface BillOrder {
  orderNumber: string;
  publicToken: string;
  status: OrderStatus;
  placedAt: string;
  /** How the total was worked out, all computed by the server. */
  subtotal: number;
  tax: number;
  serviceCharge: number;
  discount: number;
  total: number;
  paid: number;
  inProgress: number;
  due: number;
  awaitingConfirmation: number;
  paymentStatus: PaymentStatus | null;
  /** Whether anything on this order can be paid right now. */
  payable: boolean;
  items: BillItem[];
}

/** "My bill": the caller's own orders and what they still owe. */
export interface DinerBill {
  displayName: string;
  visitId: string;
  orders: BillOrder[];
  /** Payable now. */
  due: number;
  /** A payment has been started and staff have not confirmed it yet. */
  inProgress: number;
  paid: number;
  /** Owed, but the kitchen has not confirmed those orders yet, so not payable yet. */
  awaitingConfirmation: number;
}

/** One guest's line on the table bill: a name and amounts, never their items. */
export interface TableBillGuest {
  displayName: string;
  you: boolean;
  due: number;
  inProgress: number;
  awaitingConfirmation: number;
}

export interface TableBill {
  visitId: string;
  tableName: string;
  guests: TableBillGuest[];
  due: number;
  inProgress: number;
  paid: number;
  awaitingConfirmation: number;
}

export interface SettlementGuestLine {
  displayName: string;
  you: boolean;
  amount: number;
}

/** One act of paying: the guest's own bill, or the whole table's. Paid only once staff confirm it. */
export interface Settlement {
  code: string;
  kind: SettlementKind;
  status: SettlementStatus;
  paymentMethod: PaymentMethod;
  total: number;
  pending: number;
  confirmed: number;
  createdAt: string;
  confirmedAt: string | null;
  reference: string | null;
  /** UPI: when the guest said they had paid. A claim only; the payment stays pending until staff confirm. */
  customerConfirmedAt: string | null;
  guests: SettlementGuestLine[];
}

export type TablePayState = 'NO_ORDERS' | 'UNPAID' | 'PARTIALLY_PAID' | 'PAID';

/** One table visit on the owner's live view. */
export interface TableSessionSummary {
  id: number;
  visitCode: string;
  tableName: string;
  status: 'OPEN' | 'CLOSED';
  openedAt: string;
  /** When any guest last acted. */
  lastActivityAt: string;
  guestCount: number;
  /** Payable now. */
  due: number;
  /** A payment started and waiting for staff to confirm. */
  inProgress: number;
  /** Received. Counted once per payment record. */
  paid: number;
  /** Owed, but the kitchen has not confirmed the order yet. */
  awaitingConfirmation: number;
  payState: TablePayState;
  /** Nobody has acted for longer than the configured time. */
  idle: boolean;
  /** Idle while money is still owed: never closed automatically, waiting for a person. */
  needsStaff: boolean;
  closedAt: string | null;
  /** STAFF, or IDLE_EXPIRED when it closed itself. */
  closeReason: string | null;
}

export interface VisitOrder {
  id: number;
  orderNumber: string;
  status: OrderStatus;
  placedAt: string;
  total: number;
  paid: number;
  inProgress: number;
  due: number;
  awaitingConfirmation: number;
  payState: TablePayState;
  items: BillItem[];
}

export interface VisitGuest {
  id: number;
  displayName: string;
  joinedAt: string;
  lastSeenAt: string;
  due: number;
  inProgress: number;
  paid: number;
  awaitingConfirmation: number;
  payState: TablePayState;
  orders: VisitOrder[];
}

/** One payment record against one order. A combined payment is one row per order it covered. */
export interface VisitPayment {
  id: number;
  orderNumber: string;
  guestName: string;
  method: PaymentMethod;
  provider: string | null;
  amount: number;
  status: PaymentStatus;
  /** SEPARATE, COMBINED, or null for a payment staff recorded at the counter. */
  settlementKind: SettlementKind | null;
  settlementCode: string | null;
  transactionId: string | null;
  at: string;
}

export interface SettlementOrderLine {
  orderNumber: string;
  guestName: string;
  amount: number;
  paymentStatus: PaymentStatus;
}

/** A settlement as staff see it: who is paying, for which orders, and what is waiting to be confirmed. */
export interface SettlementStaff {
  id: number;
  code: string;
  kind: SettlementKind;
  status: SettlementStatus;
  tableName: string;
  payerName: string;
  paymentMethod: PaymentMethod;
  total: number;
  pending: number;
  confirmed: number;
  createdAt: string;
  /** When the guest said they had paid (UPI), or null. */
  customerConfirmedAt: string | null;
  /** The UPI reference the guest typed in, or null. Check it against the bank statement. */
  customerReference: string | null;
  orders: SettlementOrderLine[];
}

export interface TableSessionDetail {
  summary: TableSessionSummary;
  closedBy: string | null;
  guests: VisitGuest[];
  settlements: SettlementStaff[];
  payments: VisitPayment[];
}

export interface PublicTable {
  restaurantId: number;
  restaurantName: string;
  logo: string | null;
  primaryColor: string | null;
  secondaryColor: string | null;
  tableName: string;
  paymentMethods: PaymentMethod[];
  /** Google's "write a review" link; null until the owner sets one. */
  googleReviewUrl: string | null;
  /** The restaurant's Google Maps page (with its reviews); needs no API key. */
  googleMapsUrl: string | null;
  /** This restaurant's UPI details; null unless UPI is on and the owner uploaded a QR. */
  upi: PublicUpi | null;
}

/** What a guest needs to pay this restaurant by UPI. */
export interface PublicUpi {
  qrUrl: string;
  upiId: string;
  recipientName: string | null;
}

export interface GoogleReview {
  authorName: string;
  authorUrl: string | null;
  authorPhotoUrl: string | null;
  rating: number | null;
  text: string | null;
  relativeTime: string | null;
  reviewUrl: string | null;
}

/** Google rating summary; rating is null when the server has no Places API key. */
export interface PublicReviews {
  writeReviewUrl: string | null;
  mapsUrl: string | null;
  rating: number | null;
  ratingCount: number | null;
  reviews: GoogleReview[];
}

export interface PublicMenuCategory {
  id: number;
  name: string;
  description: string | null;
  items: MenuItem[];
}

export interface OrderTracking {
  orderNumber: string;
  /** Why the restaurant cancelled it, in words. Null if not cancelled or no reason was given. */
  cancelReasonLabel?: string | null;
  /** What this guest already rated the order; null if not rated. */
  feedbackRating?: number | null;
  feedbackComment?: string | null;
  tax?: number;
  serviceCharge?: number;
  taxIncluded?: number;
  taxName?: string | null;
  taxRate?: number;
  serviceChargeRate?: number;
  pricesIncludeTax?: boolean;

  status: OrderStatus;
  paymentStatus: PaymentStatus | null;
  amountPaid: number;
  balanceDue: number;
  tableName: string;
  subtotal: number;
  discount: number;
  couponCode: string | null;
  total: number;
  createdAt: string;
  items: { itemName: string; quantity: number; unitPrice: number; lineTotal: number; options?: SelectedOption[] }[];
}

export interface RealtimeMessage {
  /** Null (or absent) for transient messages such as a service request update: they are never stored. */
  notificationId?: number | null;
  restaurantId: number;
  orderId: number | null;
  title: string;
  message: string;
  notificationType: string;
  createdAt: string;
  /** Structured meaning (NEW_ORDER, WAITER_CALL, BILL_REQUEST...); absent on an older server, where the type and text are used. */
  eventKind?: string | null;
  /** Set for order events; null (or absent on an older server) otherwise. */
  orderNumber?: string | null;
  /** Plain decimal text such as "450.00"; null (or absent on an older server) when not about an order. */
  orderTotal?: string | null;
}

/** Published on /topic/platform (platform owner only). Not stored, so it cannot be replayed. */
export interface PlatformRealtimeEvent {
  /** A UUID, unique per event. */
  eventId: string;
  type: string;
  title: string;
  message: string;
  restaurantId: number;
  restaurantName: string;
  createdAt: string;
}


/* ---------- payments & revenue analytics ---------- */

/** Methods, providers and statuses come from the server so new ones need no frontend change. */
export interface PaymentMeta {
  currency: string;
  timezone: string;
  methods: {
    key: PaymentMethod;
    label: string;
    digital: boolean;
    cash: boolean;
    dinerSelectable: boolean;
    providers: { key: string; label: string }[];
  }[];
  statuses: { key: PaymentStatus; label: string; captured: boolean }[];
  cardTypes: { key: CardType; label: string }[];
  staff: { id: number; name: string }[];
}

export type PeriodPreset =
  | 'TODAY'
  | 'YESTERDAY'
  | 'LAST_7_DAYS'
  | 'LAST_30_DAYS'
  | 'THIS_MONTH'
  | 'LAST_MONTH'
  | 'CUSTOM';

export type Granularity = 'HOURLY' | 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY';

export interface AnalyticsFilters {
  period: PeriodPreset;
  from?: string;
  to?: string;
  granularity?: Granularity;
  methods: PaymentMethod[];
  providers: string[];
  statuses: PaymentStatus[];
  staffId?: number | null;
}

/** percent is null when the previous period had nothing to compare against. */
export interface Delta {
  absolute: number;
  percent: number | null;
}

export interface AnalyticsTotals {
  gross: number;
  transactions: number;
  uniqueOrders: number;
  averageTransaction: number;
  refunds: number;
  refundCount: number;
  net: number;
}

export interface MethodRow {
  key: PaymentMethod;
  label: string;
  digital: boolean;
  gross: number;
  transactions: number;
  shareOfCollected: number;
  averageTransaction: number;
  refunds: number;
  refundCount: number;
  net: number;
  previousGross: number;
  previousTransactions: number;
  deltaPercent: number | null;
  pendingCount: number;
  failedCount: number;
  cancelledCount: number;
}

export interface ProviderRow {
  key: string;
  label: string;
  method: PaymentMethod;
  methodLabel: string;
  known: boolean;
  gross: number;
  transactions: number;
  shareOfMethod: number;
  shareOfCollected: number;
  averageTransaction: number;
  refunds: number;
  refundCount: number;
  previousGross: number;
  deltaPercent: number | null;
  pendingCount: number;
  failedCount: number;
  cancelledCount: number;
}

export interface AnalyticsOverview {
  period: {
    preset: PeriodPreset;
    from: string;
    to: string;
    previousFrom: string;
    previousTo: string;
    previousEndExclusive: string;
    granularity: Granularity;
    currency: string;
    timezone: string;
  };
  totals: AnalyticsTotals;
  previousTotals: AnalyticsTotals;
  deltas: {
    gross: Delta;
    net: Delta;
    transactions: Delta;
    averageTransaction: Delta;
    refunds: Delta;
  };
  /** Billing figure for orders placed; null while payment filters are active. */
  sales: {
    /** Billed to guests, including any tax and service charge. */
    grossSales: number;
    ordersPlaced: number;
    /** Tax added on top of menu prices. */
    taxCollected: number;
    /** Tax already inside menu prices. */
    taxIncluded: number;
    serviceCharges: number;
    /** Gross sales less all tax: what the dishes and service charge earned. */
    salesExcludingTax: number;
  } | null;
  methods: MethodRow[];
  providers: ProviderRow[];
  series: {
    granularity: Granularity;
    buckets: string[];
    rows: { key: PaymentMethod; label: string; amounts: number[]; counts: number[] }[];
  };
  heatmap: {
    amounts: number[][];
    counts: number[][];
    hourlyAmounts: number[];
    hourlyCounts: number[];
  };
  open: {
    pendingCount: number;
    pendingAmount: number;
    failedCount: number;
    failedAmount: number;
    cancelledCount: number;
    cancelledAmount: number;
  };
  insights: { id: string; tone: 'positive' | 'negative' | 'neutral' | 'info'; text: string }[];
  definitions: { key: string; label: string; formula: string }[];
}

export interface PaymentTransaction {
  id: number;
  reference: string;
  hasGatewayReference: boolean;
  orderId: number;
  orderNumber: string;
  method: PaymentMethod;
  methodLabel: string;
  provider: string | null;
  providerLabel: string | null;
  cardType: CardType | null;
  amount: number;
  refundedAmount: number;
  status: PaymentStatus;
  statusLabel: string;
  timestamp: string;
  recordedBy: string | null;
  settledAt: string | null;
  currency: string;
}

export interface PaymentTransactionDetail {
  transaction: PaymentTransaction;
  orderTotal: number;
  tableName: string | null;
  refunds: { id: number; amount: number; reason: string | null; refundedAt: string; refundedBy: string | null }[];
  otherPaymentsOnOrder: PaymentTransaction[];
}

export interface Reconciliation {
  date: string;
  timezone: string;
  currency: string;
  cash: {
    collected: number;
    refunded: number;
    expected: number;
    counts: {
      id: number;
      expected: number;
      counted: number;
      difference: number;
      note: string | null;
      countedBy: string | null;
      recordedAt: string;
    }[];
    byStaff: { staffId: number | null; name: string; transactions: number; amount: number }[];
  };
  digital: { method: PaymentMethod; label: string; transactions: number; collected: number }[];
  digitalTotal: number;
  refundsTotal: number;
  pending: { count: number; amount: number };
  settlement: {
    gatewayConnected: boolean;
    settledCount: number;
    settledAmount: number;
    unsettledCount: number;
    unsettledAmount: number;
  };
  closing: {
    grossCollected: number;
    refunds: number;
    netCollected: number;
    cashNet: number;
    digitalNet: number;
    otherNet: number;
    latestCashDifference: number | null;
  };
}


/* ---------- table-side service requests ---------- */

export type ServiceRequestType =
  | 'CALL_WAITER'
  | 'DRINKING_WATER'
  | 'REQUEST_BILL'
  | 'CUTLERY'
  | 'NAPKINS'
  | 'EXTRA_PLATES'
  | 'CLEANING'
  | 'ASSISTANCE'
  | 'OTHER';

export type ServiceRequestStatus = 'PENDING' | 'ACKNOWLEDGED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

/** What a guest sees of a request at their table. `note` is null for other guests' requests. */
export interface PublicServiceRequest {
  requestNumber: string;
  type: ServiceRequestType;
  typeLabel: string;
  status: ServiceRequestStatus;
  note: string | null;
  mine: boolean;
  /** True when the guest asked for something already open, so the existing request came back. */
  alreadyRequested: boolean;
  createdAt: string;
  acknowledgedAt: string | null;
  completedAt: string | null;
}

export interface ServiceRequestEvent {
  from: ServiceRequestStatus | null;
  to: ServiceRequestStatus;
  actor: string;
  at: string;
}

export interface StaffServiceRequest {
  id: number;
  requestNumber: string;
  type: ServiceRequestType;
  typeLabel: string;
  status: ServiceRequestStatus;
  urgent: boolean;
  note: string | null;
  tableId: number;
  tableName: string;
  requestedBy: string | null;
  assignedToUserId: number | null;
  assignedToName: string | null;
  createdAt: string;
  acknowledgedAt: string | null;
  completedAt: string | null;
  /** Computed by the server, so a tablet with the wrong clock still shows the right wait. */
  waitingSeconds: number;
  history: ServiceRequestEvent[];
}


/* ---------- service charge and tax ---------- */

/** A restaurant's own rules. Nothing is assumed: a restaurant that never sets them has zeros. */
export interface BillingSettings {
  taxName: string;
  taxRate: number;
  serviceChargeRate: number;
  pricesIncludeTax: boolean;
  /** Printed on receipts. Tidied by the server, never verified; blank or absent means none. */
  taxRegistrationNumber?: string | null;
}


/* ---------- the restaurant's team ---------- */

export interface StaffMember {
  id: number;
  name: string;
  email: string;
  role: RoleName;
  active: boolean;
  /** The signed-in person: they cannot change their own account here. */
  you: boolean;
  /** Whether the signed-in person may change this account. The server enforces it either way. */
  canManage: boolean;
  createdAt: string;
}


/* ---------- menu import and onboarding ---------- */

export interface MenuImportProblem {
  /** 0 means the file as a whole. The header row is line 1. */
  line: number;
  field: string;
  message: string;
}

export interface MenuImportRow {
  line: number;
  category: string;
  name: string;
  price: number;
  veg: boolean;
  available: boolean;
  prepMinutes: number;
  /** NEW will be created; EXISTS is already in that category and is skipped. */
  status: 'NEW' | 'EXISTS';
  newCategory: boolean;
}

export interface MenuImportPreview {
  canImport: boolean;
  rows: MenuImportRow[];
  problems: MenuImportProblem[];
  newCategories: string[];
  toCreate: number;
  alreadyExist: number;
}

export interface MenuImportResult {
  created: number;
  skippedExisting: number;
  categoriesCreated: number;
}

export interface OnboardingStep {
  key: string;
  title: string;
  hint: string;
  route: string;
  done: boolean;
  required: boolean;
}

export interface OnboardingProgress {
  steps: OnboardingStep[];
  requiredDone: number;
  requiredTotal: number;
  percent: number;
  complete: boolean;
  nextKey: string | null;
}


/* ---------- uploaded photos ---------- */

/** A stored photo. `url` goes in the dish's image field; it is public and unguessable. */
export interface UploadedImage {
  url: string;
  width: number;
  height: number;
  sizeBytes: number;
}


/* ---------- private customer feedback ---------- */

/** What a guest sent, as saved. */
export interface FeedbackSaved {
  rating: number;
  comment: string | null;
}

export interface FeedbackEntry {
  id: number;
  orderNumber: string;
  tableName: string | null;
  rating: number;
  comment: string | null;
  createdAt: string;
}

export interface FeedbackSummary {
  count: number;
  /** One decimal place; null when nobody has rated yet (never 0). */
  average: number | null;
  /** How many ratings of 1, 2, 3, 4 and 5 stars, in that order. */
  distribution: number[];
  lowCount: number;
  lowPercent: number;
  days: number;
}


/* ---------- printable receipt ---------- */

export interface ReceiptRestaurant {
  name: string;
  address: string | null;
  city: string | null;
  state: string | null;
  phone: string | null;
  /** Printed as the owner entered it (tidied, never verified). */
  taxRegistrationNumber: string | null;
}

export interface ReceiptItem {
  name: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
  /** "Group: Option". Their prices are already inside unitPrice. */
  options: string[];
}

export interface ReceiptPayment {
  /** As it should read: "Cash", "UPI (Google Pay)". */
  method: string;
  amount: number;
  paidAt: string | null;
}

/** What is printed. Amounts are the ones recorded on the order, never recalculated. */
export interface Receipt {
  restaurant: ReceiptRestaurant;
  orderNumber: string;
  tableName: string | null;
  placedAt: string;
  items: ReceiptItem[];
  subtotal: number;
  discount: number;
  couponCode: string | null;
  serviceCharge: number;
  serviceChargeRate: number;
  tax: number;
  taxName: string | null;
  taxRate: number;
  taxIncluded: number;
  pricesIncludeTax: boolean;
  total: number;
  payments: ReceiptPayment[];
  amountPaid: number;
  refunded: number;
  balanceDue: number;
  /** PAID, PART PAID, UNPAID, REFUNDED or NO PAYMENT DUE. */
  paymentHeading: string;
}


/* ---------- printable QR cards ---------- */

/** What a sheet of table cards needs: the restaurant's name and its active tables in natural order. */
export interface QrCards {
  restaurantName: string;
  tables: { id: number; tableName: string; tableCode: string }[];
}

// ---------------------------------------------------------------- QR landing page

/** The landing cards the app offers. A server may still list retired keys; `knownActions` drops them. */
export type LandingActionKey = 'REWARDS' | 'REVIEW' | 'MENU' | 'WIFI' | 'ASSISTANCE';

export interface PublicLanding {
  landingEnabled: boolean;
  restaurantId: number;
  restaurantName: string;
  logo: string | null;
  primaryColor: string | null;
  secondaryColor: string | null;
  tableName: string;
  tagline: string | null;
  coverImageUrl: string | null;
  announcement: string | null;
  hours: { known: boolean; open: boolean; label: string; week: { day: string; hours: string }[] };
  links: {
    googleReviewUrl: string | null;
    instagramUrl: string | null;
    youtubeUrl: string | null;
    facebookUrl: string | null;
    websiteUrl: string | null;
    directionsUrl: string | null;
    phone: string | null;
    phoneDial: string | null;
  };
  wifi: { ssid: string; security: string; hasPassword: boolean } | null;
  rewards: { spendPerPoint: number; redeemPoints: number; rewardText: string | null; terms: string | null } | null;
  actions: string[];
}

export interface WifiDetails {
  ssid: string;
  security: string;
  password: string | null;
}

export interface RewardsInfo {
  enabled: boolean;
  spendPerPoint: number | null;
  redeemPoints: number;
  rewardText: string | null;
  terms: string | null;
  member: {
    code: string;
    balance: number;
    pointsToGo: number;
    canRedeem: boolean;
    history: { kind: 'EARN' | 'REDEEM'; points: number; note: string | null; at: string }[];
  } | null;
}

export interface RewardClaim {
  pointsAdded: number;
  rewards: RewardsInfo;
}

export interface RewardRedeem {
  memberCode: string;
  pointsSpent: number;
  newBalance: number;
  rewardText: string | null;
}

export interface AdminLanding {
  landingEnabled: boolean;
  tagline: string | null;
  coverImageUrl: string | null;
  announcement: string | null;
  openingHours: string | null;
  googleReviewUrl: string | null;
  instagramUrl: string | null;
  youtubeUrl: string | null;
  facebookUrl: string | null;
  websiteUrl: string | null;
  mapUrl: string | null;
  wifiEnabled: boolean;
  wifiSsid: string | null;
  wifiSecurity: string;
  /** Whether a password is saved. The password itself is never sent to the admin screen. */
  wifiPasswordSet: boolean;
  /** No longer offered: always saved as false. */
  feedbackEnabled: boolean;
  assistanceEnabled: boolean;
  rewardsEnabled: boolean;
  rewardsSpendPerPoint: number;
  rewardsRedeemPoints: number;
  rewardsRewardText: string | null;
  rewardsTerms: string | null;
  actions: string[];
  allActions: string[];
}

export type AdminLandingInput = Omit<AdminLanding, 'wifiPasswordSet' | 'allActions'> & {
  /** null keeps the saved password. */
  wifiPassword: string | null;
};

export interface RewardMember {
  memberCode: string;
  balance: number;
  redeemPoints: number;
  pointsToGo: number;
  canRedeem: boolean;
  rewardText: string | null;
}

/* ---------- platform owner (super admin) ---------- */

export interface PlatformOverview {
  total: number;
  pending: number;
  approved: number;
  rejected: number;
  suspended: number;
}

export type PlatformSortBy = 'createdAt' | 'name' | 'status';
export type SortDirection = 'asc' | 'desc';

export interface PlatformRestaurantSummary {
  id: number;
  name: string;
  ownerName: string;
  ownerEmail: string;
  email: string | null;
  phone: string | null;
  city: string | null;
  state: string | null;
  country: string | null;
  createdAt: string;
  status: RestaurantStatus;
  statusReason: string | null;
  reviewedAt: string | null;
}

export interface PlatformRestaurantDetail extends PlatformRestaurantSummary {
  slug: string | null;
  address: string | null;
  ownerPhone: string | null;
  reviewedByName: string | null;
  rejectionReason: string | null;
  suspensionReason: string | null;
  tableCount: number;
  menuItemCount: number;
  documents: PlatformDocument[];
}

/** Always empty today; typed loosely-but-safely so a future backend can fill it in. */
export interface PlatformDocument {
  id: number;
  name: string;
}