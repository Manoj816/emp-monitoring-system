import { Amplify } from "aws-amplify";
import {
  signIn as amplifySignIn,
  signOut as amplifySignOut,
  fetchAuthSession,
  getCurrentUser,
} from "aws-amplify/auth";

/**
 * Admin sign-in against the Cognito user pool. The backend accepts the resulting
 * ID token as a Bearer JWT and grants ROLE_ADMIN (see ApiAuthFilter).
 */
const userPoolId = import.meta.env.VITE_COGNITO_USER_POOL_ID as string | undefined;
const userPoolClientId = import.meta.env.VITE_COGNITO_CLIENT_ID as string | undefined;

export const authConfigured = Boolean(userPoolId && userPoolClientId);

if (authConfigured) {
  Amplify.configure({
    Auth: { Cognito: { userPoolId: userPoolId!, userPoolClientId: userPoolClientId! } },
  });
}

export async function signIn(email: string, password: string): Promise<void> {
  const result = await amplifySignIn({ username: email, password });
  if (!result.isSignedIn) {
    throw new Error(`Sign-in needs another step: ${result.nextStep.signInStep}`);
  }
}

export function signOut(): Promise<void> {
  return amplifySignOut();
}

/** The signed-in admin's email, or null when nobody is signed in. */
export async function currentUserEmail(): Promise<string | null> {
  try {
    const user = await getCurrentUser();
    return user.signInDetails?.loginId ?? user.username;
  } catch {
    return null;
  }
}

/** A fresh ID token (Amplify refreshes expired ones), or null when signed out. */
export async function idToken(): Promise<string | null> {
  try {
    const session = await fetchAuthSession();
    return session.tokens?.idToken?.toString() ?? null;
  } catch {
    return null;
  }
}
