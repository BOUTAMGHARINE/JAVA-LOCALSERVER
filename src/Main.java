public class Main {
    public static void main(String[] args) {
        // On définit le port d'écoute
        int port = 8080;
        
        // On crée notre objet Server
        Server server = new Server(port);
        
        // On démarre le serveur
        server.start();
    }
}