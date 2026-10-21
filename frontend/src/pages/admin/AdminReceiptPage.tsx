import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { orderApi } from '../../api';
import { ReceiptScreen } from '../../components/ReceiptScreen';
import { ErrorMessage } from '../../components/ui';

/** A receipt for staff to print for any of their restaurant's orders. */
export default function AdminReceiptPage() {
  const { id = '' } = useParams();
  const receipt = useQuery({ queryKey: ['order-receipt', id], queryFn: () => orderApi.receipt(Number(id)), retry: false });

  if (receipt.isLoading) return <p className="muted">Loading the receipt…</p>;
  if (receipt.error || !receipt.data) return <ErrorMessage error={receipt.error} />;
  return <ReceiptScreen receipt={receipt.data} backTo="/admin/orders" backLabel="Back to orders" />;
}
