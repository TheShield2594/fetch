import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { type Account, api } from "../api";

/**
 * Who is signed in. Asked once per tab; `refresh()` asks again.
 *
 * `isAdmin` is false until the answer arrives, so the admin-only parts of
 * Settings appear a moment late rather than flashing up for someone who isn't
 * the admin. The server refuses those changes either way.
 */
type AccountContextValue = {
  account: Account | null;
  isAdmin: boolean;
  refresh: () => void;
};

const AccountContext = createContext<AccountContextValue | null>(null);

export function AccountProvider({ children }: { children: ReactNode }) {
  const [account, setAccount] = useState<Account | null>(null);
  const [isAdmin, setIsAdmin] = useState(false);
  const [epoch, setEpoch] = useState(0);

  useEffect(() => {
    let cancelled = false;
    api
      .getMe()
      .then((me) => {
        if (cancelled) return;
        setAccount(me.account);
        setIsAdmin(me.isAdmin);
      })
      // Offline, or signed out underneath us (the client handles that 401 by
      // sending the app back to sign-in). Nothing to show either way.
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [epoch]);

  const refresh = useCallback(() => setEpoch((n) => n + 1), []);
  const value = useMemo(() => ({ account, isAdmin, refresh }), [account, isAdmin, refresh]);
  return <AccountContext.Provider value={value}>{children}</AccountContext.Provider>;
}

const SIGNED_OUT: AccountContextValue = { account: null, isAdmin: false, refresh: () => {} };

/** Falls back to "nobody, not admin" outside a provider, which hides admin-only UI. */
export function useAccount(): AccountContextValue {
  return useContext(AccountContext) ?? SIGNED_OUT;
}
