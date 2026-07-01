import { NativeModule, requireNativeModule } from "expo";
import { PermissionsAndroid } from "react-native";

// NOTE: most of these imports are unused in the code, but they are used in the documentation of various methods.
import type {
  Callback,
  Notification,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  MessagePayload,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  RegisteredPayload,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  RegistrationFailedPayload,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  UnregisteredPayload,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  ErrorPayload,
  Distributor,
  CallbackData,
} from "./ExpoUnifiedPush.types";

type ExpoUnifiedPushModuleEvents = {
  message(event: CallbackData): void;
};

/**
 * The native module that is used to bridge the kotlin native code with the javascript code.
 */
export declare class ExpoUnifiedPushModule extends NativeModule<ExpoUnifiedPushModuleEvents> {
  /**
   * Get the list of UnifiedPush distributors available on the device.
   * The list will always include a low-priority distributor that uses Firebase Cloud Messaging (FCM).
   * @returns a list of distributor identifiers.
   */
  getDistributors(): Distributor[];

  /**
   * Get the distributor selected for using UnifiedPush.
   * If no distributor has been selected, it will return `null`.
   * @returns The selected distributor identifier or `null`.
   */
  getSavedDistributor(): string | null;

  /**
   * Select a distributor for using UnifiedPush. This will be saved in the device storage.
   * You can also pass `null` to clear the saved distributor, but this will clear all instances registered with the distributor.
   * @param distributor The distributor identifier to select.
   */
  saveDistributor(distributor: string | null): void;

  /**
   * Register a device for push notifications, connecting to the selected distributor.
   *
   * The returned promise will automatically be rejected if app is running inside an emulator or no distributor is selected.
   * If you had already registered your device with a distributor, this ensures the connection is working, hence why you should always call this method on application startup.
   * External distributors will be favored over embedded distributors.
   *
   * @param vapid The VAPID public key that identifies the server that will be sending the push notifications. This key can be generated using the `web-push` package from npm. More information can be found at https://github.com/web-push-libs/web-push.
   * @param instance This param is used to identify different registrations in the same device, for example if the app has an account swithcer feature, you can set this to the current user ID.
   * This param is not mandatory. If you won't have multiple users on the same device at the same time, you can omit it.
   */
  registerDevice(vapid: string, instance?: string): Promise<void>;

  /**
   * Unregister a device for push notifications.
   * This method will remove a registration for a specific instance from the distributor.
   * The distributor subscriber will not receive the `unregistered` event after calling this method.
   *
   * @param instance The same `instance` param that was used on the `registerDevice` method.
   */
  unregisterDevice(instance?: string): void;

  /** Internal method used to show a local notification.
   * It is prefixed with an underscore to avoid conflicts with the exported `showLocalNotification` function.
   * It is important to use `showLocalNotification` without the underscore instead of this one because of the type checking.
   */
  private __showLocalNotification(notification: string): Promise<void>;

  /**
   * Check if this module is running inside an emulator.
   * Internal method used only in other calls.
   */
  private __isEmulator(): boolean;
}

/**
 * Subscribe to the messages sent from the distributor background service.
 * This callback will be called whenever the distributor sends a message to the device.
 * It will not run if the app is terminated but notifications will still be delivered even if this callback is not registered.
 * It will also receive error messages created when displaying notifications on the background service.
 *
 * @param fn The function that will receive the distributor messages.
 *
 * This function will always receive an object with the properties `action` and `data`:
 * - `data`: The data sent from the distributor. Varies depending on the `action` property.
 * - `action`: The action that was performed by the distributor. Can be one of the following:
 * - - `"message"`: A push notification was received. Data for this type is {@link MessagePayload}.
 *        The text in `data.message` will contain the JSON-encoded payload of the push notification if `data.decrypted` is `true`.
 *        Otherwise, it will contain the raw encrypted payload.
 * - - `"registered"`: The device has been registered with a distributor. Data for this type is {@link RegisteredPayload}. This is the data that is needed for the backend to send a push notification to a specific device.
 * - - `"registrationFailed"`: The device failed to register with a distributor. Data for this type is {@link RegistrationFailedPayload}.
 * - - `"unregistered"`: The device has been unregistered from a distributor. Data for this type is {@link UnregisteredPayload}.
 * - - `"error"`: An error occurred while receiving a message from a distributor. Data for this type is {@link ErrorPayload}.
 */
export function subscribeDistributorMessages(fn: Callback) {
  const listener = module.addListener("message", fn);
  return () => listener.remove();
}

/**
 * Request the necessary permissions for the distributor to send messages to the device.
 * This method will request the `POST_NOTIFICATIONS` android permission.
 * If this module is running inside an emulator, this function will always return `"denied"`, as notifications are not supported on emulators.
 *
 * @returns A promise that resolves to 'granted' if the permission was granted, 'denied' if the user denied the permission, or 'never_ask_again' if the user chose to never ask again.
 */
export function requestPermissions() {
  // @ts-ignore: Private field access is intentional
  if (module.__isEmulator()) {
    return Promise.resolve("denied");
  }

  return PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
  );
}

/**
 * Check if the `POST_NOTIFICATIONS` android permission has been granted.
 * If this module is running inside an emulator, this function will always return `false`, as notifications are not supported on emulators
 *
 * @returns A promise that resolves to `true` if the permission has been granted, `false` otherwise.
 */
export function checkPermissions() {
  // @ts-ignore: Private field access is intentional
  if (module.__isEmulator()) {
    return Promise.resolve(false);
  }

  return PermissionsAndroid.check(
    PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
  );
}

/**
 * Show a local notification. This is a helper function that will show a notification using the native notification API.
 * It can be used to test the different types of notifications that can be received from the distributor.
 * This skips renderer resolution and asks for the full notification object in the rendering shape.
 * @param notification The notification complete notification object for rendering.
 */
export async function showLocalNotification(notification: Notification) {
  try {
    const json = JSON.stringify(notification);
    // @ts-ignore: Private field access is intentional
    await module.__showLocalNotification(json);
  } catch (error) {
    console.error(error);
  }
}

// This call loads the native module object from the JSI.
const module = requireNativeModule<ExpoUnifiedPushModule>("ExpoUnifiedPush");
export default module;
