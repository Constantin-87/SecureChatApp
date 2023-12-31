package com.securechatapp;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.servlet.http.HttpSession;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint(value = "/chat", configurator = GetHttpSessionConfigurator.class)
public class ChatEndpoint {

    private static final Map<Session, Integer> sessionUserIdMap = new ConcurrentHashMap<>();
    private static final Map<Integer, Session> userIdSessionMap = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> userLastSeen = new ConcurrentHashMap<>();  // To be used in activity tracker (not implemented yet)
    private static final Map<Integer, String> userIdUsernameMap = new ConcurrentHashMap<>(); // Map to hold user IDs and usernames

    @OnOpen
    public void onOpen(Session session, EndpointConfig config) {
        HttpSession httpSession = (HttpSession) config.getUserProperties().get(HttpSession.class.getName());
        Integer userId = null;
        String username = "";
        if (httpSession != null) {
            userId = (Integer) httpSession.getAttribute("userId");
            username = httpSession.getAttribute("username").toString();
        }
        if (userId != null && !username.isEmpty()) {
            loginUser(username, userId, session);
            updateUserListForAllClients();
        } else {
            try {
                session.close(); // Close the session if the user is not authenticated
                AppLogger.warning("Session closed as no user is authenticated");
            } catch (IOException e) {
                AppLogger.severe("onOpen - Error closing session: " + e.getMessage());
            }
        }
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        try ( JsonReader reader = Json.createReader(new StringReader(message))) {
            JsonObject jsonMessage = reader.readObject();

            // Check if 'type' is provided and not null
            String type = jsonMessage.getString("type", null);
            if (type == null) {
                AppLogger.severe("onMessage - Message type is missing or null.");
                return; // Exit the method if 'type' is missing
            }

            int senderId = sessionUserIdMap.get(session); // Get the sender's id
            int recipientId, conversationId;

            DatabaseManager dbManager = new DatabaseManager();
            switch (type) {
                case "message":
                    String messageContent = jsonMessage.getString("content", null);
                    recipientId = jsonMessage.getInt("recipient");

                    // Check if 'recipient' and 'content' are provided and not null
                    if (messageContent == null) {
                        AppLogger.severe("onMessage - 'recipient' or 'content' is missing or null.");
                        return; // Exit the method if required fields are missing
                    }

                    // Retrieve or create conversation ID
                    conversationId = DatabaseManager.ensureConversation(senderId, recipientId);

                    // Save and send the message
                    DatabaseManager.saveChatMessage(senderId, recipientId, conversationId, messageContent);
                    sendMessageToUser(senderId, recipientId, messageContent, conversationId);
                    break;

                case "requestChatHistory":
                    recipientId = jsonMessage.getInt("recipientId");
                    conversationId = DatabaseManager.ensureConversation(senderId, recipientId);
                    List<Message> chatHistory = dbManager.getChatHistory(conversationId);
                    sendChatHistory(session, conversationId, chatHistory);
                    break;

                case "requestSymKey":
                    recipientId = jsonMessage.getInt("recipientId");
                    conversationId = DatabaseManager.ensureConversation(senderId, recipientId);
                    byte[] encryptedSymKey = DatabaseManager.getEncryptedSymmetricKey(senderId, conversationId);
                    sendSymKey(session, encryptedSymKey, conversationId);
                    break;

                case "requestPrivateKey":

                    String privateKey = DatabaseManager.getPrivateKeyByUserId(senderId);
                    sendPrivateKey(session, privateKey);

                    break;

                default:
                    AppLogger.severe("onMessage - Unknown message type: " + type);
                    break;
            }
        } catch (Exception e) {
            AppLogger.severe("onMessage - Error processing message: " + e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session) {
        Integer userId = sessionUserIdMap.get(session);
        logoutUser(userId);
        updateUserListForAllClients();
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        AppLogger.severe("onError - WebSocket error: " + throwable.getMessage());

    }

    public static void loginUser(String username, Integer userId, Session session) {
        AppLogger.info("Logging in user: " + username + " with userId: " + userId);
        sessionUserIdMap.put(session, userId);
        userIdSessionMap.put(userId, session);
        userLastSeen.put(userId, System.currentTimeMillis());
        userIdUsernameMap.put(userId, username); // Store the username as well
    }

    private static void logoutUser(Integer userId) {
        Session session = userIdSessionMap.remove(userId);
        if (session != null) {
            sessionUserIdMap.remove(session);
            try {
                session.close();
            } catch (IOException e) {
                AppLogger.severe("logoutUser - Error closing session for user " + userIdUsernameMap.get(userId) + ": " + e.getMessage());
            }
        }
        userLastSeen.remove(userId);
        userIdUsernameMap.remove(userId); // Remove the user from the username map as well
    }

    private static void updateUserListForAllClients() {
        JsonArrayBuilder jsonArrayBuilder = Json.createArrayBuilder();

        // Retrieve user IDs and usernames from the map and add them to the JSON array as objects
        for (Integer userId : userIdSessionMap.keySet()) {
            String username = userIdUsernameMap.get(userId); // Get the username from the map
            if (username != null) {
                JsonObject userObject = Json.createObjectBuilder()
                        .add("id", userId)
                        .add("username", username)
                        .build();
                jsonArrayBuilder.add(userObject);
            }
        }

        String usersJson = Json.createObjectBuilder()
                .add("type", "userListUpdate")
                .add("users", jsonArrayBuilder)
                .build()
                .toString();

        // Send the updated user list to all connected clients
        for (Session session : userIdSessionMap.values()) {
            if (session.isOpen()) {
                try {
                    session.getBasicRemote().sendText(usersJson);
                } catch (IOException e) {
                    AppLogger.severe("updateUserListForAllClients - Error sending user list: " + e.getMessage());
                }
            }
        }
    }

    private void sendSymKey(Session session, byte[] encryptedSymKey, int conversationId) {
        try {
            AppLogger.info("sendSymKey - Sending sym key of size: " + encryptedSymKey.length);

            // Convert conversationId to bytes (assuming conversationId is an int)
            ByteBuffer combinedBuffer = ByteBuffer.allocate(Integer.BYTES + encryptedSymKey.length);
            combinedBuffer.putInt(conversationId);  // Add conversationId to the buffer
            combinedBuffer.put(encryptedSymKey);    // Add encryptedSymKey to the buffer

            // Flip the buffer to set the limit to the current position and position to zero
            combinedBuffer.flip();

            // Send the combined data as binary
            session.getBasicRemote().sendBinary(combinedBuffer);
        } catch (IOException ex) {
            AppLogger.severe("sendSymKey - Error sending sym key to user: " + ex.getMessage());
        }
    }

    private void sendMessageToUser(Integer senderId, Integer recipientId, String content, int conversationId) {
        try {
            String senderUsername = userIdUsernameMap.get(senderId); // Get sender's username from the map
            JsonObject jsonMessage = Json.createObjectBuilder()
                    .add("type", "message")
                    .add("senderId", senderId)
                    .add("senderUsername", senderUsername) // Include the sender's username
                    .add("content", content) // This should be the encrypted content
                    .add("conversationId", conversationId)
                    .build();

            // Log the message content before sending
            AppLogger.info("sendMessageToUser - Sending message: " + jsonMessage.toString());

            // Send to recipient
            Session recipientSession = userIdSessionMap.get(recipientId);
            if (recipientSession != null && recipientSession.isOpen()) {
                recipientSession.getBasicRemote().sendText(jsonMessage.toString());
            }

            // Also send to sender to update their UI
            Session senderSession = userIdSessionMap.get(senderId);
            if (senderSession != null && senderSession.isOpen()) {
                senderSession.getBasicRemote().sendText(jsonMessage.toString());
            }
        } catch (IOException e) {
            AppLogger.severe("sendMessageToUser - Error sending message: " + e.getMessage());
        }
    }

    // Method to send chat history to the user, now includes the conversationId
    private void sendChatHistory(Session session, int conversationId, List<Message> chatHistory) {
        JsonArrayBuilder historyArrayBuilder = Json.createArrayBuilder();
        for (Message message : chatHistory) {
            // Add each message to the JSON array
            historyArrayBuilder.add(Json.createObjectBuilder()
                    .add("id", message.getId())
                    .add("senderId", message.getSenderId())
                    .add("recipientId", message.getRecipientId())
                    .add("content", message.getContent())
                    .add("timestamp", message.getTimestamp().toString())
                    .add("sender_username", message.getSenderUsr())
                    .add("recipient_username", message.getRecipientUsr())
                    .build());
        }

        // Build the final JSON object including the conversationId
        JsonObject chatHistoryJson = Json.createObjectBuilder()
                .add("type", "chatHistoryResponse")
                .add("conversationId", conversationId) // Include the conversationId in the JSON object
                .add("messages", historyArrayBuilder)
                .build();

        try {
            // Log the chat history JSON before sending
            AppLogger.info("sendChatHistory - Sending chat history: " + chatHistoryJson.toString());

            session.getBasicRemote().sendText(chatHistoryJson.toString());
        } catch (IOException e) {
            AppLogger.severe("sendChatHistory - Error sending chat history: " + e.getMessage());
        }
    }

    private void sendPrivateKey(Session session, String privateKey) {

        JsonObject privateKeyJson = Json.createObjectBuilder()
                .add("type", "privateKeyUpdate")
                .add("privateKey", privateKey)
                .build();
        try {
            // Log the privateKey string before sending
            AppLogger.info("privateKey string before sending: " + privateKeyJson.toString());

            session.getBasicRemote().sendText(privateKeyJson.toString());
        } catch (IOException e) {
            AppLogger.severe("sendChatHistory - Error sending chat history: " + e.getMessage());
        }

    }
}
