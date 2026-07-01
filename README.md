# expo-unified-push

Expo integration of the android UnifiedPush library.

> [!WARNING]  
> This library is only supported on Android at the moment. For iOS suport, we recommend using the [RN Push Notifications](https://github.com/react-native-push-notification/ios) library or the [Expo Notifications](https://docs.expo.dev/versions/latest/sdk/notifications/) library.

## API documentation

Main documentation is available at [ExpoUnifiedPushModule](https://juandjara.github.io/expo-unified-push/classes/ExpoUnifiedPushModule.html) typedoc pages.

## Installation in managed Expo projects

For [managed](https://docs.expo.dev/archive/managed-vs-bare/) Expo projects, please follow the installation instructions in the [API documentation](#api-documentation).

## Installation in bare React Native projects

For bare React Native projects, you must ensure that you have [installed and configured the `expo` package](https://docs.expo.dev/bare/installing-expo-modules/) before continuing.

### Add the package to your npm dependencies

```
npm install expo-unified-push
```

## Example integration into your app

To see an example implementation of the library, you can check the [App.tsx](./example/App.tsx) file in the [example](./example) folder.

## Custom notification payloads

By default, this module expects the decrypted push payload sent from the backned to be a JSON string shaped like this:

```json
{ "id": 123, "title": "...", "body": "...", "url": "..." }
```

... and some other properties not included here.

It uses this shape to build a native android notification and present it without touching the JS/React side of things, so it works even if the system has killed or backgrounded your app.

But there are cases where you cannot exactly control the shape of data coming from your backend. In these cases, you can implement a custom `PushPayloadRenderer` to format your data and register it via the `payloadRendererClass` config plugin option like this:

```js
// app.config.js
{
  plugins: [
    ['expo-unified-push', { payloadRendererClass: 'com.example.app.push.MyPushPayloadRenderer' }],
  ],
}
```

This is an example of what a custom `PushPayloadRenderer` can look like:

```kt
// android/app/src/main/java/com/example/app/push/MyPushPayloadRenderer.kt
package com.example.app.push

import android.content.Context
import dev.djara.expounifiedpush.NotificationContent
import dev.djara.expounifiedpush.PushPayloadRenderer

class MyPushPayloadRenderer : PushPayloadRenderer {
  override fun render(context: Context, instance: String, decrypted: String): NotificationContent? {
    // parse `decrypted` however your payload is shaped, or return null to show nothing
    return NotificationContent(id = 1, title = "...", body = decrypted)
  }
}
```

The class needs a public no-arg constructor and must be compiled into your app. Since managed/CNG Expo projects can't hand-edit `android/`, you can put it in a [local Expo module](https://docs.expo.dev/modules/get-started/#creating-a-local-module) (e.g. under `./modules`) rather than directly under `android/app/`, so it survives a `expo prebuild --clean`.

The `render` method of your custom `PushPayloadRenderer` is called from a small android service (`ExpoUPService.kt`) and has no connection to the JS or React side so it must not depend on the RN bridge and should be fast and synchronous.

## Sending notifications from your backend

To send notifications from your backend, use the [`web-push`](https://www.npmjs.com/package/web-push) npm library or something similar. It will handle all encoding of parameters for you. Remember to set up VAPID keys for improved security (more info on the `web-push` readme)

## Contributing

Contributions are very welcome! Just make sure to keep the code style consistent with the rest of the codebase and ask before adding any new dependencies.
