import { lazy, Suspense, type CSSProperties } from 'react';
import { Link, Navigate, Route, Routes } from 'react-router-dom';
import { FLOOR_ROLES, KITCHEN_ROLES, MANAGER_ROLES, PLATFORM_ROLES, homeFor, useAuth } from './auth/AuthContext';
import RequireRole from './auth/RequireRole';
import { ErrorBoundary } from './components/ErrorBoundary';
import { NotificationCenterProvider } from './components/notifications/NotificationCenter';
import { BrandMark, Spinner } from './components/ui';
import CheckoutPage from './pages/customer/CheckoutPage';
import EntryPage from './pages/customer/EntryPage';
import OrderPage from './pages/customer/OrderPage';
import PayPage from './pages/customer/PayPage';

// Staff screens load on demand so diners on the QR menu never download admin code or the websocket client.
const LandingPage = lazy(() => import('./pages/customer/LandingPage'));
const LoginPage = lazy(() => import('./pages/auth/LoginPage'));
const RegisterOwnerPage = lazy(() => import('./pages/auth/RegisterOwnerPage'));
const AdminLayout = lazy(() => import('./pages/admin/AdminLayout'));
const DashboardPage = lazy(() => import('./pages/admin/DashboardPage'));
const OrdersPage = lazy(() => import('./pages/admin/OrdersPage'));
const MenuItemsPage = lazy(() => import('./pages/admin/MenuItemsPage'));
const CategoriesPage = lazy(() => import('./pages/admin/CategoriesPage'));
const TablesPage = lazy(() => import('./pages/admin/TablesPage'));
const InventoryPage = lazy(() => import('./pages/admin/InventoryPage'));
const CouponsPage = lazy(() => import('./pages/admin/CouponsPage'));
const PaymentSettingsPage = lazy(() => import('./pages/admin/PaymentSettingsPage'));
const PaymentAnalyticsPage = lazy(() => import('./pages/admin/PaymentAnalyticsPage'));
const LandingSettingsPage = lazy(() => import('./pages/admin/LandingSettingsPage'));
const BillingSettingsPage = lazy(() => import('./pages/admin/BillingSettingsPage'));
const StaffPage = lazy(() => import('./pages/admin/StaffPage'));
const MenuImportPage = lazy(() => import('./pages/admin/MenuImportPage'));
const FeedbackPage = lazy(() => import('./pages/admin/FeedbackPage'));
const NotificationSettingsPage = lazy(() => import('./pages/admin/NotificationSettingsPage'));
const QrCardsPage = lazy(() => import('./pages/admin/QrCardsPage'));
const AdminReceiptPage = lazy(() => import('./pages/admin/AdminReceiptPage'));
const PlatformLayout = lazy(() => import('./pages/platform/PlatformLayout'));
const PlatformDashboardPage = lazy(() => import('./pages/platform/PlatformDashboardPage'));
const RestaurantListPage = lazy(() => import('./pages/platform/RestaurantListPage'));
const RestaurantDetailPage = lazy(() => import('./pages/platform/RestaurantDetailPage'));
const PlatformSettingsPage = lazy(() => import('./pages/platform/PlatformSettingsPage'));
const ReceiptPage = lazy(() => import('./pages/customer/ReceiptPage'));
const KitchenPage = lazy(() => import('./pages/kitchen/KitchenPage'));
const LiveTablesPage = lazy(() => import('./pages/admin/LiveTablesPage'));
const FloorPage = lazy(() => import('./pages/floor/FloorPage'));
const ServiceRequestsPage = lazy(() => import('./pages/admin/ServiceRequestsPage'));
const ServiceRequestsPanel = lazy(() =>
  import('./pages/admin/ServiceRequestsPage').then((m) => ({ default: m.ServiceRequestsPanel })),
);

/** Everyone who works the floor or the kitchen: all of them may see and answer table requests. */
const STAFF_ROLES = [...new Set([...FLOOR_ROLES, ...KITCHEN_ROLES])];

function Home() {
  const { status, user } = useAuth();
  if (status === 'loading') return <Spinner />;
  if (user) {
    const target = homeFor(user.role);
    if (target !== '/') return <Navigate to={target} replace />;
  }
  return (
    <main className="landing">
      <div className="landing-inner">
        <span className="rise">
          <BrandMark />
        </span>
        <h1 className="rise" style={{ '--i': 1 } as CSSProperties}>
          Kashmir Menu
        </h1>
        <p className="rise" style={{ '--i': 2 } as CSSProperties}>
          Diners: scan the QR code on your table to see the menu and order. Restaurant staff: sign in below.
        </p>
        <div className="landing-actions rise" style={{ '--i': 3 } as CSSProperties}>
          <Link className="btn btn-primary" to="/login">
            Staff sign in
          </Link>
          <Link className="btn" to="/register">
            Register a restaurant
          </Link>
        </div>
        <div className="steps rise" style={{ '--i': 4 } as CSSProperties}>
          <div>
            <strong>1 · Scan</strong>Point your camera at the table QR code.
          </div>
          <div>
            <strong>2 · Order</strong>Browse the menu and add dishes together.
          </div>
          <div>
            <strong>3 · Track</strong>Follow your order live until it arrives.
          </div>
        </div>
      </div>
    </main>
  );
}

export default function App() {
  return (
    // Wraps the whole route tree: a render failure anywhere shows a message and a way out rather
    // than a blank page. Outside Suspense, so a lazy chunk that fails to load is caught here
    <ErrorBoundary>
      {/* Live notifications and sound for signed-in staff; renders nothing for diners and visitors. */}
      <NotificationCenterProvider>
      <Suspense fallback={<Spinner />}>
        <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/menu/:qr" element={<EntryPage />} />
        <Route path="/menu/:qr/home" element={<LandingPage />} />
        <Route path="/menu/:qr/cart" element={<CheckoutPage />} />
        <Route path="/menu/:qr/pay" element={<PayPage />} />
        <Route path="/order/:token" element={<OrderPage />} />
        <Route path="/receipt/:token" element={<ReceiptPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterOwnerPage />} />

        <Route
          path="/admin"
          element={
            <RequireRole roles={MANAGER_ROLES}>
              <AdminLayout />
            </RequireRole>
          }
        >
          <Route index element={<DashboardPage />} />
          <Route path="live" element={<LiveTablesPage />} />
          <Route path="requests" element={<ServiceRequestsPanel />} />
          <Route path="orders" element={<OrdersPage />} />
          <Route path="orders/:id/receipt" element={<AdminReceiptPage />} />
          <Route path="menu" element={<MenuItemsPage />} />
          <Route path="menu/import" element={<MenuImportPage />} />
          <Route path="categories" element={<CategoriesPage />} />
          <Route path="tables" element={<TablesPage />} />
          <Route path="inventory" element={<InventoryPage />} />
          <Route path="coupons" element={<CouponsPage />} />
          <Route path="revenue" element={<PaymentAnalyticsPage />} />
          <Route path="payments" element={<PaymentSettingsPage />} />
          <Route path="billing" element={<BillingSettingsPage />} />
          <Route path="staff" element={<StaffPage />} />
          <Route path="feedback" element={<FeedbackPage />} />
          <Route path="notifications" element={<NotificationSettingsPage />} />
          <Route path="tables/cards" element={<QrCardsPage />} />
          <Route path="landing" element={<LandingSettingsPage />} />
          <Route path="reviews" element={<Navigate to="/admin" replace />} />
        </Route>

        <Route
          path="/platform-admin"
          element={
            <RequireRole roles={PLATFORM_ROLES}>
              <PlatformLayout />
            </RequireRole>
          }
        >
          <Route index element={<PlatformDashboardPage />} />
          <Route path="restaurants" element={<RestaurantListPage key="all" />} />
          <Route path="restaurants/pending" element={<RestaurantListPage key="pending" presetStatus="PENDING" />} />
          <Route path="restaurants/approved" element={<RestaurantListPage key="approved" presetStatus="APPROVED" />} />
          <Route path="restaurants/rejected" element={<RestaurantListPage key="rejected" presetStatus="REJECTED" />} />
          <Route path="restaurants/:id" element={<RestaurantDetailPage />} />
          <Route path="settings" element={<PlatformSettingsPage />} />
        </Route>

        <Route
          path="/kitchen"
          element={
            <RequireRole roles={KITCHEN_ROLES}>
              <KitchenPage />
            </RequireRole>
          }
        />

        <Route
          path="/floor"
          element={
            <RequireRole roles={FLOOR_ROLES}>
              <FloorPage />
            </RequireRole>
          }
        />

        <Route
          path="/requests"
          element={
            <RequireRole roles={STAFF_ROLES}>
              <ServiceRequestsPage />
            </RequireRole>
          }
        />

        <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
      </NotificationCenterProvider>
    </ErrorBoundary>
  );
}
