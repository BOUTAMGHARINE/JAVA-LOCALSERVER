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
    private final List<Integer> ports; // On gère maintenant une LISTE de ports
    private Selector selector;
    private final ConfigLoader.ServerConfig config;

    // Le constructeur reçoit maintenant la config chargée (via ConfigLoader)
    public Server(ConfigLoader.ServerConfig config) {
        this.config = config;
        this.ports = config.ports;
    }

    public void start() {
        try {
            // 1. Un seul Selector pour tout le monde
            this.selector = Selector.open();
            
            // 2. On ouvre une porte d'entrée (ServerSocketChannel) pour CHAQUE port
            for (int port : ports) {
                ServerSocketChannel serverChannel = ServerSocketChannel.open();
                serverChannel.bind(new InetSocketAddress("localhost", port));
                serverChannel.configureBlocking(false); // Non-bloquant obligatoire
                
                // On enregistre chaque canal sur le MÊME selector
                serverChannel.register(this.selector, SelectionKey.OP_ACCEPT);
                
                System.out.println("[INIT] Écoute initialisée sur le port : " + port);
            }

            System.out.println("====== SERVEUR CONFIGURÉ ET DÉMARRÉ ======");
            System.out.println("En attente d'événements sur " + ports.size() + " port(s)...");
            System.out.println("==========================================");

            // 3. La Boucle Événementielle reste EXACTEMENT la même !
            while (true) {
                this.selector.select(); 

                Set<SelectionKey> selectedKeys = this.selector.selectedKeys();
                System.out.println("[INFO] " + selectedKeys + " événement(s) à traiter...-----------------------------------");
                Iterator<SelectionKey> iterator = selectedKeys.iterator();

                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove(); 

                    if (!key.isValid()) continue;

                    try {
                        if (key.isAcceptable()) {
                            // On doit récupérer le bon ServerSocketChannel qui a déclenché l'événement
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

    // handleAccept prend maintenant le canal spécifique en paramètre
    private void handleAccept(ServerSocketChannel serverChannel) throws IOException {
        SocketChannel clientChannel = serverChannel.accept();
        clientChannel.configureBlocking(false);
        clientChannel.register(this.selector, SelectionKey.OP_READ);
        
        // Petite info utile : on affiche sur quel port local le client est arrivé
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

        // Séparer en headers et body si présent
        String[] parts = requestText.split("\r?\n\r?\n", 2);
        String headerPart = parts[0];
        String bodyPart = parts.length > 1 ? parts[1] : "";

        String[] headerLines = headerPart.split("\r?\n");
        String requestLine = headerLines[0];
        System.out.println("[REQUÊTE] " + requestLine);

        String[] reqTokens = requestLine.split(" ");
        String method = reqTokens.length > 0 ? reqTokens[0] : "";
        String path = reqTokens.length > 1 ? reqTokens[1] : "/";

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < headerLines.length; i++) {
            String line = headerLines[i];
            int idx = line.indexOf(":");
            if (idx > 0) {
                String name = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                headers.put(name.toLowerCase(), value);
            }
        }

        byte[] responseBodyBytes;
        String statusLine = "HTTP/1.1 200 OK";
        String contentType = "text/html; charset=UTF-8";

        if ("POST".equalsIgnoreCase(method)) {
            // Echo du body pour l'instant
            String bodyPreview = bodyPart;
            // Si Content-Length indique plus de données, on ne gère pas la lecture incrémentale ici
            String html = "<html><body><h1>POST reçu</h1><pre>" + escapeHtml(bodyPreview) + "</pre></body></html>";
            responseBodyBytes = html.getBytes(StandardCharsets.UTF_8);
        } else if ("GET".equalsIgnoreCase(method)) {
            // Tentative de servir un fichier depuis le root configuré
            if (path.contains("..")) {
                statusLine = "HTTP/1.1 403 Forbidden";
                String html = "<html><body><h1>403 Forbidden</h1></body></html>";
                responseBodyBytes = html.getBytes(StandardCharsets.UTF_8);
            } else {
                String normalized = path.split("\\?")[0];
                if (normalized.endsWith("/")) normalized += this.config.indexFile;
                if (normalized.equals("/")) normalized = "/" + this.config.indexFile;
                Path filePath = Paths.get(this.config.rootDir + normalized);
                if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                    responseBodyBytes = Files.readAllBytes(filePath);
                    // très basique: si fichier html
                    if (normalized.endsWith(".html") || normalized.endsWith(".htm")) {
                        contentType = "text/html; charset=UTF-8";
                    } else {
                        contentType = "application/octet-stream";
                    }
                } else {
                    statusLine = "HTTP/1.1 404 Not Found";
                    String html = "<html><body><h1>404 Not Found</h1><p>" + escapeHtml(path) + "</p></body></html>";
                    responseBodyBytes = html.getBytes(StandardCharsets.UTF_8);
                }
            }
        } else {
            statusLine = "HTTP/1.1 405 Method Not Allowed";
            String html = "<html><body><h1>405 Method Not Allowed</h1></body></html>";
            responseBodyBytes = html.getBytes(StandardCharsets.UTF_8);
        }

        String httpResponse = statusLine + "\r\n" +
                              "Content-Type: " + contentType + "\r\n" +
                              "Content-Length: " + responseBodyBytes.length + "\r\n" +
                              "Connection: close\r\n" +
                              "\r\n";

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

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}