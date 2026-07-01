const {
  withAndroidManifest,
  createRunOncePlugin,
  AndroidConfig,
} = require("expo/config-plugins");

const pkg = require("./package.json");

/**
 * The config plugin `withPushPayloadRenderer` writes the classname of the app custom payload renderer (if any)
 * to a `<meta-data>` block with this name in the AndroidManifest file.
 * This is used to read the class from the classname via reflection in `ExpoUPService`.
 */
const PAYLOAD_RENDERER_META_DATA = "dev.djara.expounifiedpush.PAYLOAD_RENDERER";

/** Add a custom payload renderer that turns the content of received push notifications
 * into something `ExpoUPService` can use for presenting an android notification with `NotificationCompat`.
 * This is useful only if the decryped push payload for your app
 * isn't the default `{id,title,body,...}` JSON shape that the module expects.
 *
 * @param {import('expo/config-plugins').ExpoConfig} config
 * @param {{ payloadRendererClass?: string }} [props]
 *
 * `payloadRendererClass` is the fully-qualified class name of your `PushPayloadRenderer` implementation
 * (e.g. `"com.example.app.MyPushPayloadRenderer"`).
 *
 * The class must have a public no-arg constructor and live somewhere that gets compiled into the app
 * (e.g. a local Expo module). see README.md for the full setup.
 */
function withPushPayloadRenderer(config, { payloadRendererClass } = {}) {
  if (!payloadRendererClass) {
    return config;
  }
  return withAndroidManifest(config, (config) => {
    const mainApplication = AndroidConfig.Manifest.getMainApplicationOrThrow(
      config.modResults,
    );
    AndroidConfig.Manifest.addMetaDataItemToMainApplication(
      mainApplication,
      PAYLOAD_RENDERER_META_DATA,
      payloadRendererClass,
    );
    return config;
  });
}

module.exports = createRunOncePlugin(
  withPushPayloadRenderer,
  pkg.name,
  pkg.version,
);
