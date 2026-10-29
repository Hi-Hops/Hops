import { useEffect, useState } from 'react';

/** 서버가 안내한 재시도 대기 중에도 입력은 보존하고 자동 요청은 보내지 않는다. */
export function useRetryAfter() {
  const [remaining, setRemaining] = useState(0);
  useEffect(() => {
    if (remaining <= 0) return;
    const timer = setTimeout(() => {
      setRemaining((current) => Math.max(0, current - 1));
    }, 1000);
    return () => {
      clearTimeout(timer);
    };
  }, [remaining]);
  return {
    remaining,
    wait: (seconds: number) => {
      if (Number.isFinite(seconds) && seconds > 0)
        setRemaining((current) => Math.max(current, Math.ceil(seconds)));
    },
  };
}
