// Reexport the native module. On web, it will be resolved to ExpoUnifiedPushModule.web.ts
// and on native platforms to ExpoUnifiedPushModule.ts
export {
  default,
  ExpoUnifiedPushModule,
  requestPermissions,
  checkPermissions,
  showLocalNotification,
} from "./ExpoUnifiedPushModule";
export * from "./ExpoUnifiedPush.types";
