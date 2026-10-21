import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { publicApi } from '../../api';
import { ReceiptScreen } from '../../components/ReceiptScreen';
import { ErrorMessage } from '../../components/ui';

/** A guest's receipt, reached with their order's private token. Outside the ordering screens so it prints cleanly. */
export default function ReceiptPage() {
  const { token = '' } = useParams();
  const receipt = useQuery({ queryKey: ['public-receipt', token], queryFn: () => publicApi.receipt(token), retry: false });

  if (receipt.isLoading) return <p className="receipt-message muted">Loading your receipt…</p>;
  if (receipt.error || !receipt.data) {
    return (
      <div className="receipt-message">
        <ErrorMessage error={receipt.error} />
      </div>
    );
  }
  return <ReceiptScreen receipt={receipt.data} backTo={`/order/${token}`} backLabel="Back to my order" />;
}
