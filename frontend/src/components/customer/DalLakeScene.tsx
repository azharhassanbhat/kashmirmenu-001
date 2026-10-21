import { memo, useId } from 'react';

/**
 * Illustrated Dal Lake at dusk: snow ridges over the Zabarwan range, a poplar shoreline and
 * houseboats with lit windows reflected in the water. Inline vector, so it costs no request and
 * scales to any hero size. Swap for licensed photography by passing an image to <Hero>.
 */
export const DalLakeScene = memo(function DalLakeScene({ className }: { className?: string }) {
  const id = useId().replace(/:/g, '');
  const u = (n: string) => `url(#${id}-${n})`;
  return (
    <svg className={className} viewBox="0 0 400 260" preserveAspectRatio="xMidYMid slice" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id={`${id}-sky`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#14070C" />
          <stop offset="0.32" stopColor="#4A1220" />
          <stop offset="0.52" stopColor="#A82A2E" />
          <stop offset="0.63" stopColor="#F06A2E" />
          <stop offset="0.69" stopColor="#FFC45C" />
        </linearGradient>
        <radialGradient id={`${id}-sun`} cx="0.5" cy="0.5" r="0.5">
          <stop offset="0" stopColor="#FFE3A6" />
          <stop offset="0.25" stopColor="#FFC45C" stopOpacity="0.9" />
          <stop offset="1" stopColor="#FF4A24" stopOpacity="0" />
        </radialGradient>
        <linearGradient id={`${id}-water`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#7A2026" />
          <stop offset="0.35" stopColor="#3A0F18" />
          <stop offset="1" stopColor="#10080B" />
        </linearGradient>
        <linearGradient id={`${id}-glint`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#FFC45C" stopOpacity="0.85" />
          <stop offset="1" stopColor="#FF4A24" stopOpacity="0" />
        </linearGradient>
      </defs>
      <rect width="400" height="180" fill={u('sky')} />
      <circle cx="292" cy="150" r="70" fill={u('sun')} opacity="0.75" />
      <circle cx="292" cy="152" r="13" fill="#FFE3A6" />
      <path d="M210 92h120M236 99h70M40 108h90M60 114h40" stroke="#FF8A4A" strokeWidth="1.4" strokeLinecap="round" opacity="0.35" />
      <path
        d="M0 150L28 131L50 139L80 116L104 128L130 102L151 117L171 94L191 110L216 90L241 108L263 99L290 121L318 104L350 124L380 110L400 119V180H0Z"
        fill="#5B1E2C"
      />
      <path
        d="M122 110L130 102L139 112L134 110L130 114L126 109ZM163 101L171 94L180 103L175 101L171 105L167 100ZM208 97L216 90L226 99L220 97L216 101L212 96ZM74 121L80 116L86 122L80 121Z"
        fill="#E8A8A0"
        opacity="0.75"
      />
      <path d="M0 165L40 149L72 158L112 143L152 160L200 149L252 163L300 147L342 158L400 149V180H0Z" fill="#33101A" />
      <rect y="175" width="400" height="85" fill={u('water')} />
      <path d="M262 180h60v5h-60zM270 190h44v3h-44zM278 200h28v2h-28zM284 210h16v2h-16z" fill={u('glint')} />
      <path d="M0 175L2.6 161L5.2 175ZM8 175a4 4 0 0 1 8 0ZM14 175L16.6 165L19.2 175ZM20 175L22.6 153L25.2 175ZM29 175L31.6 165L34.2 175ZM38 175L40.6 165L43.2 175ZM43 175a6 6 0 0 1 12 0ZM48 175a5 5 0 0 1 10 0ZM53 175a4 4 0 0 1 8 0ZM60 175L62.6 161L65.2 175ZM64 175a6 6 0 0 1 12 0ZM71 175L73.6 161L76.2 175ZM78 175L80.6 165L83.2 175ZM82 175a5 5 0 0 1 10 0ZM91 175a5 5 0 0 1 10 0ZM101 175L103.6 161L106.2 175ZM106 175a4 4 0 0 1 8 0ZM111 175a6 6 0 0 1 12 0ZM120 175a6 6 0 0 1 12 0ZM130 175L132.6 165L135.2 175ZM135 175L137.6 161L140.2 175ZM142 175L144.6 153L147.2 175ZM151 175L153.6 165L156.2 175ZM158 175L160.6 157L163.2 175ZM166 175a5 5 0 0 1 10 0ZM171 175a5 5 0 0 1 10 0ZM180 175a4 4 0 0 1 8 0ZM185 175a5 5 0 0 1 10 0ZM195 175L197.6 153L200.2 175ZM202 175L204.6 153L207.2 175ZM209 175L211.6 165L214.2 175ZM218 175L220.6 157L223.2 175ZM223 175a5 5 0 0 1 10 0ZM232 175a5 5 0 0 1 10 0ZM238 175L240.6 153L243.2 175ZM244 175a5 5 0 0 1 10 0ZM251 175a5 5 0 0 1 10 0ZM260 175a4 4 0 0 1 8 0ZM266 175L268.6 161L271.2 175ZM272 175L274.6 161L277.2 175ZM279 175L281.6 161L284.2 175ZM288 175L290.6 157L293.2 175ZM293 175a6 6 0 0 1 12 0ZM299 175L301.6 153L304.2 175ZM308 175L310.6 165L313.2 175ZM316 175a4 4 0 0 1 8 0ZM323 175L325.6 161L328.2 175ZM332 175L334.6 157L337.2 175ZM337 175L339.6 161L342.2 175ZM341 175a6 6 0 0 1 12 0ZM347 175L349.6 161L352.2 175ZM356 175L358.6 157L361.2 175ZM362 175a5 5 0 0 1 10 0ZM368 175L370.6 153L373.2 175ZM377 175L379.6 157L382.2 175ZM382 175L384.6 157L387.2 175ZM389 175L391.6 161L394.2 175ZM394 175L396.6 157L399.2 175Z" fill="#16080D" />
      <path d="M21 172v-7h52v7zM18 165l29 -5l29 5zM95 172v-7h40v7zM92 165l23 -5l23 5zM153 172v-7h58v7zM150 165l32 -5l32 5zM239 172v-7h46v7zM236 165l26 -5l26 5zM303 172v-7h64v7zM300 165l35 -5l35 5zM375 172v-7h34v7zM372 165l20 -5l20 5z" fill="#22090F" />
      <path d="M18 172h58l-4 6h-50zM92 172h46l-4 6h-38zM150 172h64l-4 6h-56zM236 172h52l-4 6h-44zM300 172h70l-4 6h-62zM372 172h40l-4 6h-32z" fill="#1A070C" />
      <path d="M23 167.5h4v3h-4zM32 167.5h4v3h-4zM41 167.5h4v3h-4zM59 167.5h4v3h-4zM68 167.5h4v3h-4zM97 167.5h4v3h-4zM124 167.5h4v3h-4zM133 167.5h4v3h-4zM155 167.5h4v3h-4zM164 167.5h4v3h-4zM182 167.5h4v3h-4zM191 167.5h4v3h-4zM200 167.5h4v3h-4zM241 167.5h4v3h-4zM250 167.5h4v3h-4zM259 167.5h4v3h-4zM268 167.5h4v3h-4zM277 167.5h4v3h-4zM323 167.5h4v3h-4zM332 167.5h4v3h-4zM341 167.5h4v3h-4zM359 167.5h4v3h-4zM377 167.5h4v3h-4zM386 167.5h4v3h-4zM395 167.5h4v3h-4zM404 167.5h4v3h-4z" fill="#FFC45C" />
      <path d="M23 182.8h4v18h-4zM32 182.3h4v10h-4zM41 181.5h4v14h-4zM59 181.4h4v18h-4zM68 181.7h4v10h-4zM97 182.6h4v10h-4zM124 181.5h4v18h-4zM133 182.5h4v10h-4zM155 181.9h4v10h-4zM164 182.9h4v14h-4zM182 181.7h4v14h-4zM191 181.2h4v14h-4zM200 181.4h4v18h-4zM241 181.0h4v18h-4zM250 182.3h4v18h-4zM259 181.8h4v18h-4zM268 182.0h4v10h-4zM277 182.3h4v10h-4zM323 181.8h4v10h-4zM332 181.3h4v10h-4zM341 182.2h4v14h-4zM359 182.7h4v14h-4zM377 181.7h4v18h-4zM386 181.0h4v18h-4zM395 182.1h4v10h-4zM404 182.7h4v10h-4z" fill="#FFC45C" opacity="0.28" />
      <path d="M150 214q22 6 46 0l-4 4h-38zM170 214v-9l3-1 1 10" fill="#0E0508" />
      <path d="M140 222h70" stroke="#FFC45C" strokeWidth="0.8" opacity="0.25" />
    </svg>
  );
});
