import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Server {

    private final List<Integer> ports;
    private Selector selector;
    private final ConfigLoader.ServerConfig config;

    public Server(ConfigLoader.ServerConfig config) {
        this.config = config;
        this.ports = config.ports;
    }

    public void start() {
        try {
            this.selector = Selector.open();

            for (int port : ports) {
                ServerSocketChannel serverChannel = ServerSocketChannel.open();
                serverChannel.bind(new InetSocketAddress("localhost", port));
                serverChannel.configureBlocking(false);
                serverChannel.register(this.selector, SelectionKey.OP_ACCEPT);
                System.out.println("[INIT] Écoute initialisée sur le port : " + port);
            }

            System.out.println("====== SERVEUR CONFIGURÉ ET DÉMARRÉ ======");
            System.out.println("En attente d'événements sur " + ports.size() + " port(s)...");
            System.out.println("==========================================");

            while (true) {
                this.selector.select();
                Set<SelectionKey> selectedKeys = this.selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectedKeys.iterator();

                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    if (!key.isValid()) continue;

                    try {
                        if (key.isAcceptable()) {
                            ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
                            handleAccept(serverChannel);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        }
                    } catch (IOException e) {
                        System.err.println("Erreur de connexion client : " + e.getMessage());
                        key.cancel();
                        try { key.channel().close(); } catch (IOException ignored) {}
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Erreur fatale du serveur : " + e.getMessage());
        }
    }

    private void handleAccept(ServerSocketChannel serverChannel) throws IOException {
        SocketChannel clientChannel = serverChannel.accept();
        clientChannel.configureBlocking(false);
        clientChannel.register(this.selector, SelectionKey.OP_READ);
        System.out.println("[CONNEXION] Client connecté sur le port "
                + serverChannel.socket().getLocalPort()
                + " depuis : " + clientChannel.getRemoteAddress());
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(2048);

        int bytesRead = clientChannel.read(buffer);
        if (bytesRead == -1) {
            clientChannel.close();
            key.cancel();
            return;
        }

        buffer.flip();
        String requestText = StandardCharsets.UTF_8.decode(buffer).toString();

        if (requestText.trim().isEmpty()) {  
            clientChannel.close();
            key.cancel();
            return;
        }

        String[] parts = requestText.split("\r?\n\r?\n", 2);
        String headerPart = parts[0];
        String bodyPart = parts.length > 1 ? parts[1] : "";

        String[] headerLines = headerPart.split("\r?\n");
        if (headerLines.length == 0 || headerLines[0].trim().isEmpty()) {
            sendErrorPage(clientChannel, "400", "HTTP/1.1 400 Bad Request");
            key.cancel();
            return;
        }

        String requestLine = headerLines[0];
        System.out.println("[REQUÊTE] " + requestLine);

        String[] reqTokens = requestLine.split(" ");
        if (reqTokens.length < 2) {
            sendErrorPage(clientChannel, "400", "HTTP/1.1 400 Bad Request");
            key.cancel();
            return;
        }

        String method = reqTokens[0];
        String path = reqTokens[1];

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < headerLines.length; i++) {
            String line = headerLines[i];
            int idx = line.indexOf(":");
            if (idx > 0) {
                headers.put(line.substring(0, idx).trim().toLowerCase(), line.substring(idx + 1).trim());
            }
        }

        byte[] responseBodyBytes;
        String statusLine = "HTTP/1.1 200 OK";
        String contentType = "text/html; charset=UTF-8";

        if ("POST".equalsIgnoreCase(method)) {
            // TODO: Vérifier ici la taille (Content-Length) pour la 413 plus tard
            String html = "<html><body><h1>POST reçu</h1><pre>" + escapeHtml(bodyPart) + "</pre></body></html>";
            responseBodyBytes = html.getBytes(StandardCharsets.UTF_8);
        } else if ("GET".equalsIgnoreCase(method)) {
            if (path.contains("..")) {
                sendErrorPage(clientChannel, "403", "HTTP/1.1 403 Forbidden");
                key.cancel();
                return;
            } else {
                String normalized = path.split("\\?")[0];
                if (normalized.endsWith("/")) normalized += this.config.indexFile;
                if (normalized.equals("/")) normalized = "/" + this.config.indexFile;

                Path filePath = Paths.get(this.config.rootDir + normalized);
                if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                    responseBodyBytes = Files.readAllBytes(filePath);
                    if (normalized.endsWith(".html") || normalized.endsWith(".htm")) {
                        contentType = "text/html; charset=UTF-8";
                    } else if (normalized.endsWith(".css")) {
                        contentType = "text/css";
                    } else if (normalized.endsWith(".js")) {
                        contentType = "application/javascript";
                    } else {
                        contentType = "application/octet-stream";
                    }
                } else {
                    // Erreur 404 : Fichier introuvable
                    sendErrorPage(clientChannel, "404", "HTTP/1.1 404 Not Found");
                    key.cancel();
                    return;
                }
            }
        } else {
            // Méthode inconnue ou non implémentée (ex: TRACE, OPTIONS)
            sendErrorPage(clientChannel, "405", "HTTP/1.1 405 Method Not Allowed");
            key.cancel();
            return;
        }

        // Envoi de la réponse de succès (200 OK)
        String httpResponse = statusLine + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + responseBodyBytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";

        ByteBuffer responseBuffer = ByteBuffer.allocate(httpResponse.getBytes(StandardCharsets.UTF_8).length + responseBodyBytes.length);
        responseBuffer.put(httpResponse.getBytes(StandardCharsets.UTF_8));
        responseBuffer.put(responseBodyBytes);
        responseBuffer.flip();

        while (responseBuffer.hasRemaining()) {
            clientChannel.write(responseBuffer);
        }

        clientChannel.close();
        key.cancel();
    }

    /**
     * Nouvelle méthode robuste qui cherche la page d'erreur sur le disque dur.
     * Si le fichier HTML customisé n'existe pas, elle renvoie une structure de secours.
     */
    private void sendErrorPage(SocketChannel clientChannel, String errorCode, String statusLine) throws IOException {
        byte[] bodyBytes;
        
        // On essaie de récupérer le chemin depuis le dossier "error_pages/"
        Path errorPath = Paths.get("error_pages/" + errorCode + ".html");
        
        if (Files.exists(errorPath) && Files.isRegularFile(errorPath)) {
            bodyBytes = Files.readAllBytes(errorPath);
        } else {
            // Fallback (Sécurité) au cas où le fichier HTML est supprimé ou introuvable
            String fallbackHtml = "<html><body><h1>" + statusLine + "</h1><p>Erreur " + errorCode + "</p></body></html>";
            bodyBytes = fallbackHtml.getBytes(StandardCharsets.UTF_8);
        }

        String httpResponse = statusLine + "\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";

        ByteBuffer responseBuffer = ByteBuffer.allocate(httpResponse.getBytes(StandardCharsets.UTF_8).length + bodyBytes.length);
        responseBuffer.put(httpResponse.getBytes(StandardCharsets.UTF_8));
        responseBuffer.put(bodyBytes);
        responseBuffer.flip();

        while (responseBuffer.hasRemaining()) {
            clientChannel.write(responseBuffer);
        }
        clientChannel.close();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}