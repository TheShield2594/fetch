/**
 * The format a profile PIN must have when it is *set or changed*: 4 to 8
 * digits, the same rule the TV and desktop apps' keypads enforce.
 *
 * Deliberately dependency-free (no `node:crypto`), so the website can import it
 * through `@fetch/shared/pin` and validate in the browser with the exact rule
 * the API applies.
 *
 * Only for choosing a PIN. Verifying one must keep accepting whatever was
 * stored before this rule existed (the API once took 4 to 128 characters of
 * anything), or households with such a PIN would be locked out.
 */
export const MIN_PIN_LENGTH = 4;
export const MAX_PIN_LENGTH = 8;

export const PIN_PATTERN = /^\d{4,8}$/;

export const PIN_FORMAT_ERROR = `PIN must be ${MIN_PIN_LENGTH} to ${MAX_PIN_LENGTH} digits`;

export const isValidPinFormat = (pin: string): boolean => PIN_PATTERN.test(pin);
