import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;

public class Server {
    private final int port;
    private Selector selector;
    private ServerSocketChannel serverChannel;

    // Le constructeur reçoit le port depuis le Main
    public Server(int port) {
        this.port = port;
    }

    public void start() {
        try {
            // 1. Initialisation du Selector et du ServerSocketChannel
            this.selector = Selector.open();
            this.serverChannel = ServerSocketChannel.open();
            
            // 2. Configuration : Liaison au port et passage en NON-BLOQUANT
            this.serverChannel.bind(new InetSocketAddress("localhost", this.port));
            this.serverChannel.configureBlocking(false);
            
            // 3. Enregistrement pour l'événement ACCEPT
            this.serverChannel.register(this.selector, SelectionKey.OP_ACCEPT);

            System.out.println("====== SERVEUR DÉMARRÉ ======");
            System.out.println("Écoute sur : http://localhost:" + this.port);
            System.out.println("=============================");

            // 4. La Boucle Événementielle (Event Loop)
            while (true) {
                this.selector.select(); // Attend un événement réseau

                Set<SelectionKey> selectedKeys = this.selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectedKeys.iterator();

                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove(); // Nettoie la clé lue

                    if (!key.isValid()) continue;

                    try {
                        if (key.isAcceptable()) {
                            handleAccept();
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

    private void handleAccept() throws IOException {
        SocketChannel clientChannel = this.serverChannel.accept();
        clientChannel.configureBlocking(false);
        clientChannel.register(this.selector, SelectionKey.OP_READ);
        System.out.println("[CONNEXION] Client connecté : " + clientChannel.getRemoteAddress());
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(2048);
        
        int bytesRead = clientChannel.read(buffer);
        
        if (bytesRead == -1) {
            System.out.println("[DÉCONNEXION] Le client a fermé la connexion.");
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

        System.out.println("[REQUÊTE REÇUE] -> " + requestText.split("\n")[0]);

        // Réponse HTTP basique
        String htmlBody = "<html><body><h1>🚀 Étape 1 Réussie avec Server.java !</h1></body></html>";
        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                              "Content-Type: text/html; charset=UTF-8\r\n" +
                              "Content-Length: " + htmlBody.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                              "Connection: close\r\n" + 
                              "\r\n" + 
                              htmlBody;

        ByteBuffer responseBuffer = ByteBuffer.wrap(httpResponse.getBytes(StandardCharsets.UTF_8));
        while (responseBuffer.hasRemaining()) {
            clientChannel.write(responseBuffer);
        }

        clientChannel.close();
        key.cancel();
    }
}