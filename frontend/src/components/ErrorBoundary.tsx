import { Component, type ErrorInfo, type ReactNode } from 'react';

/**
 * Catches a render-time failure so it shows as a message instead of a blank screen.
 *
 * This is the whole of the frontend monitoring that ships: there is no telemetry endpoint, so
 * nothing is transmitted anywhere. A browser cannot write to Prometheus, and collecting these
 * centrally would mean a new public, unauthenticated write endpoint — a security decision rather
 * than a monitoring one, and deliberately not taken here.
 *
 * What it does instead: tells the user something broke and offers a way out, and logs the detail
 * to the console where a developer or a support call can retrieve it. The server-side error rate
 * (`http_server_requests_seconds_count{status=~"5.."}`) already covers failures that originate in
 * the API; this covers the ones that only exist in the browser.
 *
 * No user identifier, no page contents and no form data are logged — only the error itself.
 */
interface Props {
  children: ReactNode;
  /** Shown instead of the default message, e.g. a diner-styled panel on the customer screens. */
  fallback?: (reset: () => void) => ReactNode;
}

interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // The component stack is the useful part: it names the component that threw, which a stack
    // trace alone often does not.
    console.error('[ui] render failed', error, info.componentStack);
  }

  private reset = () => {
    this.setState({ error: null });
  };

  render() {
    const { error } = this.state;
    if (!error) {
      return this.props.children;
    }
    if (this.props.fallback) {
      return this.props.fallback(this.reset);
    }
    return (
      <div className="card" role="alert" style={{ margin: '2rem auto', maxWidth: '32rem' }}>
        <h2>Something went wrong on this screen</h2>
        <p className="muted">
          The page could not be displayed. Your data has not been affected — nothing was saved or
          changed by this error.
        </p>
        <div className="actions">
          <button type="button" className="btn" onClick={this.reset}>
            Try again
          </button>
          <button type="button" className="btn btn-primary" onClick={() => window.location.reload()}>
            Reload the page
          </button>
        </div>
      </div>
    );
  }
}
