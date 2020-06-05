package expo.modules.notifications;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.Date;
import java.util.UUID;
import java.util.WeakHashMap;

import androidx.annotation.NonNull;
import expo.modules.notifications.notifications.JSONNotificationContentBuilder;
import expo.modules.notifications.notifications.interfaces.NotificationTrigger;
import expo.modules.notifications.notifications.model.Notification;
import expo.modules.notifications.notifications.model.NotificationContent;
import expo.modules.notifications.notifications.model.NotificationRequest;
import expo.modules.notifications.notifications.model.triggers.FirebaseNotificationTrigger;
import expo.modules.notifications.notifications.service.BaseNotificationsService;
import expo.modules.notifications.tokens.interfaces.PushTokenManager;

/**
 * Subclass of FirebaseMessagingService responsible for dispatching new tokens and remote messages.
 */
public class FirebaseListenerService extends FirebaseMessagingService {
  // Unfortunately we cannot save state between instances of a service other way
  // than by static properties. Fortunately, using weak references we can
  // be somehow sure instances of PushTokenListeners won't be leaked by this component.

  /**
   * We store this value to be able to inform new listeners of last known token.
   */
  private static String sLastToken = null;

  /**
   * A weak map of listeners -> reference. Used to check quickly whether given listener
   * is already registered and to iterate over when notifying of new token.
   */
  private static WeakHashMap<PushTokenManager, WeakReference<PushTokenManager>> sTokenListenersReferences = new WeakHashMap<>();

  /**
   * Used only by {@link PushTokenManager} instances. If you look for a place to register
   * your listener, use {@link PushTokenManager} singleton module.
   * <p>
   * Purposefully the argument is expected to be a {@link PushTokenManager} and just a listener.
   * <p>
   * This class doesn't hold strong references to listeners, so you need to own your listeners.
   *
   * @param listener A listener instance to be informed of new push device tokens.
   */
  public static void addTokenListener(PushTokenManager listener) {
    // Checks whether this listener has already been registered
    if (!sTokenListenersReferences.containsKey(listener)) {
      WeakReference<PushTokenManager> listenerReference = new WeakReference<>(listener);
      sTokenListenersReferences.put(listener, listenerReference);
      // Since it's a new listener and we know of a last valid token, let's let them know.
      if (sLastToken != null) {
        listener.onNewToken(sLastToken);
      }
    }
  }

  /**
   * Called on new token, dispatches it to {@link FirebaseListenerService#sTokenListenersReferences}.
   *
   * @param token New device push token.
   */
  @Override
  public void onNewToken(@NonNull String token) {
    super.onNewToken(token);

    for (WeakReference<PushTokenManager> listenerReference : sTokenListenersReferences.values()) {
      PushTokenManager listener = listenerReference.get();
      if (listener != null) {
        listener.onNewToken(token);
      }
    }

    sLastToken = token;
  }

  @Override
  public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
    super.onMessageReceived(remoteMessage);
    BaseNotificationsService.enqueueReceive(this, createNotification(remoteMessage));
  }

  protected Notification createNotification(RemoteMessage remoteMessage) {
    String identifier = remoteMessage.getMessageId();
    if (identifier == null) {
      identifier = UUID.randomUUID().toString();
    }
    JSONObject payload = new JSONObject(remoteMessage.getData());
    NotificationContent content = new JSONNotificationContentBuilder(this).setPayload(payload).build();
    NotificationRequest request = createNotificationRequest(identifier, content, new FirebaseNotificationTrigger(remoteMessage));
    return new Notification(request, new Date(remoteMessage.getSentTime()));
  }

  protected NotificationRequest createNotificationRequest(String identifier, NotificationContent content, FirebaseNotificationTrigger notificationTrigger) {
    return new NotificationRequest(identifier, content, notificationTrigger);
  }

  @Override
  public void onDeletedMessages() {
    super.onDeletedMessages();
    BaseNotificationsService.enqueueDropped(this);
  }
}
