import type { SVGProps } from 'react';

const iconPaths = {
  plus: 'M10 4.5v11M4.5 10h11',
  inquiries: 'M6.5 5.5H16M6.5 10H16M6.5 14.5H16M3.5 5.5h.01M3.5 10h.01M3.5 14.5h.01',
  tickets:
    'M3.5 8a1 1 0 0 1 1-1h11a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1V8ZM7 7V5.5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1V7M3.5 11h13',
  cases:
    'M10 3.5l5.5 2v4.2c0 3.2-2.2 5.6-5.5 6.8-3.3-1.2-5.5-3.6-5.5-6.8V5.5l5.5-2ZM7.5 10l1.8 1.8 3.2-3.3',
  admin: 'M4 15.5V11M4 7.5v-3M10 15.5v-6M10 6V4.5M16 15.5V13M16 9.5v-5M2.5 11h3M8.5 6h3M14.5 13h3',
  chat: 'M4.5 4.5h11a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H9l-3.5 2.5v-2.5h-1a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1Z',
} as const;

export type NavIconName = keyof typeof iconPaths;

type NavIconProps = SVGProps<SVGSVGElement> & {
  name: NavIconName;
};

/** 사이드바에서 눌러서 이동할 수 있는 항목에만 붙인다. 구역 제목에는 쓰지 않는다. */
export function NavIcon({ name, ...props }: NavIconProps) {
  return (
    <svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true" {...props}>
      <path
        d={iconPaths[name]}
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
