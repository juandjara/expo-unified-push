import ExpoUnifiedPush, {
  checkPermissions,
  Distributor,
  requestPermissions,
  showLocalNotification,
} from "expo-unified-push";
import { subscribeDistributorMessages } from "expo-unified-push/ExpoUnifiedPushModule";
import { useEffect, useState } from "react";
import {
  Alert,
  Button,
  Image,
  SafeAreaView,
  ScrollView,
  Text,
  TouchableOpacity,
  View,
} from "react-native";

// NOTE: this variable is read from the .env.local file, not included in the repository.
// Read more about this in the documentation for the `registerDevice` method.
const SERVER_VAPID_KEY = process.env.EXPO_PUBLIC_SERVER_VAPID_KEY;

export default function App() {
  const [list, setList] = useState(ExpoUnifiedPush.getDistributors());
  const [selected, setSelected] = useState(
    ExpoUnifiedPush.getSavedDistributor()
  );

  useEffect(() => {
    return subscribeDistributorMessages(({ action, data }) => {
      console.log("distributor message", action, data);
      setList(ExpoUnifiedPush.getDistributors());
      if (action === "registered") {
        /** When you receive a "registered" action, you should do the following:
         * - create a record on your backend database for `data`, using `data.pubKey` as identifier
         * - save the `data.pubKey` in the device storage. We will need it if we receive an "unregistered" action.
         */
      }
      if (action === "unregistered") {
        /** When you receive a "unregistered" action, you should do the following:
         * - delete the record on your backend database for this device, using the `data.pubKey` saved in the device storage to find it.
         * - remove the `data.pubKey` saved in the device storage
         */
      }
      if (action === "error") {
        // Nothing special to do here.
        console.error("distributor error: ", data);
      }
      if (action === "message") {
        // NOTE: here you can add some extra logic that will run after a notification is displayed.
        // For example, you can use this to update the notification count inside the app.
      }
    });
  }, []);

  function updateState() {
    setList(ExpoUnifiedPush.getDistributors());
    setSelected(ExpoUnifiedPush.getSavedDistributor());
  }

  async function checkNotificationPermissions() {
    const granted = await checkPermissions();
    if (granted) {
      return true;
    } else {
      const state = await requestPermissions();
      if (state === "granted") {
        Alert.alert("Notification permissions granted");
        return true;
      } else {
        Alert.alert("Notification permissions not granted");
        return false;
      }
    }
  }

  async function register() {
    try {
      const granted = await checkNotificationPermissions();
      if (!granted) {
        throw new Error("Notification permissions not granted");
      }
      if (!SERVER_VAPID_KEY) {
        throw new Error("SERVER_VAPID_KEY is not set");
      }

      await ExpoUnifiedPush.registerDevice(SERVER_VAPID_KEY);
    } catch (err) {
      console.error(err);
    }
  }

  async function unregister() {
    try {
      await ExpoUnifiedPush.unregisterDevice();
      saveDistributor(null);
    } catch (err) {
      console.error(err);
    }
  }

  async function sendNotification() {
    try {
      await showLocalNotification({
        id: Date.now(),
        title: "Test Notification",
        body: "This is a test notification",
      });
    } catch (err) {
      console.error(err);
    }
  }

  function saveDistributor(distributorId: string | null) {
    ExpoUnifiedPush.saveDistributor(distributorId);
    updateState();
  }

  const currentDistributor = list.find(
    (distributor) => distributor.id === selected
  );

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView style={styles.container}>
        <Text style={styles.header}>Expo UnifiedPush Example</Text>
        <Group name="Current Distributor">
          {currentDistributor ? (
            <>
              <DistributorView {...currentDistributor} />
              <TouchableOpacity onPress={() => saveDistributor(null)}>
                <Text style={{ color: "#f00", padding: 4 }}>Clear</Text>
              </TouchableOpacity>
            </>
          ) : (
            <Text>
              No distributor selected. Select one from the list below before
              registering for notifications.
            </Text>
          )}
        </Group>
        <Group name="Available Distributors">
          {list.map((distributor) => (
            <DistributorView
              key={distributor.id}
              {...distributor}
              onPress={saveDistributor}
            />
          ))}
        </Group>
        <Group name="Actions">
          <View style={{ gap: 10 }}>
            <Button
              disabled={!selected || currentDistributor?.isConnected}
              title="Register"
              onPress={register}
            />
            <Button
              disabled={!selected || !currentDistributor?.isConnected}
              title="Unregister"
              onPress={unregister}
            />
            <Button title="Send test Notification" onPress={sendNotification} />
          </View>
        </Group>
      </ScrollView>
    </SafeAreaView>
  );
}

function DistributorView({
  id,
  name,
  icon,
  isInternal,
  isSaved,
  isConnected,
  onPress,
}: Distributor & {
  onPress?: (id: string) => void;
}) {
  return (
    <TouchableOpacity
      disabled={isConnected || isSaved}
      onPress={() => onPress?.(id)}
    >
      <View
        style={{
          marginVertical: 10,
          flexDirection: "row",
          alignItems: "center",
          gap: 10,
        }}
      >
        <Image source={{ uri: icon }} style={{ width: 32, height: 32 }} />
        <Text>{name}</Text>
      </View>
      <View
        style={{
          flexDirection: "row",
          alignItems: "center",
          gap: 10,
          marginBottom: 12,
        }}
      >
        <Text style={styles.badge}>{isInternal ? "Internal" : "External"}</Text>
        {isSaved ? <Text style={styles.badge}>Saved</Text> : null}
        {isConnected ? <Text style={styles.badge}>Connected</Text> : null}
      </View>
    </TouchableOpacity>
  );
}

function Group(props: { name: string; children: React.ReactNode }) {
  return (
    <View style={styles.group}>
      <Text style={styles.groupHeader}>{props.name}</Text>
      {props.children}
    </View>
  );
}

const styles = {
  header: {
    fontSize: 30,
    margin: 20,
  },
  groupHeader: {
    fontSize: 20,
    marginBottom: 20,
  },
  group: {
    marginVertical: 10,
    marginHorizontal: 20,
    backgroundColor: "#fff",
    borderRadius: 10,
    padding: 20,
  },
  container: {
    flex: 1,
    backgroundColor: "#eee",
  },
  view: {
    flex: 1,
    height: 200,
  },
  badge: {
    backgroundColor: "grey",
    color: "white",
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 8,
    fontSize: 10,
  },
};
