#!/usr/bin/env sh
# Finds out, in one run, why a coupon is not applying.
#
# Usage:  sh docs/diagnose-coupon.sh <qrIdentifier> <COUPONCODE> [baseUrl]
# Example: sh docs/diagnose-coupon.sh 7f3a9c21-... WELCOME http://localhost:3000
#
# The QR identifier is the part after /menu/ in the diner's URL.

QR="$1"
CODE="$2"
BASE="${3:-http://localhost:3000}"

if [ -z "$QR" ] || [ -z "$CODE" ]; then
  echo "Usage: sh docs/diagnose-coupon.sh <qrIdentifier> <COUPONCODE> [baseUrl]" >&2
  exit 2
fi

echo "== 1. Does the server have the coupon endpoint at all?"
STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  -H 'Content-Type: application/json' \
  -d "{\"couponCode\":\"$CODE\"}" \
  "$BASE/api/public/tables/$QR/cart/coupon")
echo "   HTTP $STATUS"
case "$STATUS" in
  404) echo "   -> 404: this backend predates the coupon feature (or the QR id is wrong)."
       echo "      Rebuild and restart the backend, then run this again." ;;
  400) echo "   -> 400: the endpoint exists and refused the coupon. The reason is in the body below." ;;
  200) echo "   -> 200: the coupon applied. Check the discount in the body below." ;;
  *)   echo "   -> unexpected; the body below should say more." ;;
esac

echo
echo "== 2. What did it actually say?"
curl -s -X POST -H 'Content-Type: application/json' \
  -d "{\"couponCode\":\"$CODE\"}" \
  "$BASE/api/public/tables/$QR/cart/coupon"

echo
echo
echo "== 3. What does the cart look like now?"
curl -s "$BASE/api/public/tables/$QR/cart"

echo
echo
echo "A coupon needs: the cart to be non-empty, the subtotal to reach the coupon's minimum,"
echo "the coupon to be active and within its dates, and uses left on its limit."
echo "The backend log also prints one line per attempt (search for \"Coupon '$CODE'\")."
