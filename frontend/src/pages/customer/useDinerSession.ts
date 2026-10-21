import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { publicApi } from '../../api';
import { ApiError } from '../../api/client';
import type { DinerMe } from '../../api/types';
import { clearStoredDiner, getStoredDiner, storeDiner } from '../../lib/dinerSession';

/**
 * Who this device is at this table. `diner.data` is the guest once they have entered a name, and null
 * until then; null is not an error, the screens ask for a name. A stored token the server no longer
 * knows (staff closed the table) is dropped, so the guest is asked again rather than shown something
 * they cannot reach.
 *
 * Shared by the menu, the checkout and the pay screens, so they all agree on who the guest is.
 */
export function useDinerSession(qr: string, enabled: boolean) {
  const queryClient = useQueryClient();
  const dinerKey = ['public-diner', qr];

  const diner = useQuery<DinerMe | null>({
    queryKey: dinerKey,
    enabled,
    retry: false,
    staleTime: Infinity,
    queryFn: async () => {
      if (!getStoredDiner(qr)) return null;
      try {
        return await publicApi.me(qr);
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) {
          clearStoredDiner(qr);
          return null;
        }
        throw error;
      }
    },
  });

  const join = useMutation({
    mutationFn: (name: string) => publicApi.join(qr, name),
    onSuccess: (joined) => {
      storeDiner(qr, { token: joined.dinerToken, displayName: joined.displayName });
           const me = {
             displayName: joined.displayName,
             visitId: joined.visitId,
           } satisfies DinerMe;
           queryClient.setQueryData(dinerKey, me);
    },
  });

  return { diner, join };
}
