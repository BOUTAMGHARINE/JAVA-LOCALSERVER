import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ConfigLoader {

    // Une structure simple pour stocker la configuration d'un serveur
    public static class ServerConfig {
        public String host = "localhost";
        public List<Integer> ports = new ArrayList<>();
        public String rootDir = "www";
        public String indexFile = "index.html";
    }

    public static ServerConfig loadConfig(String filePath) {
        ServerConfig config = new ServerConfig();
        try {
            // On utilise NIO.2 pour lire tout le fichier d'un coup
            String content = new String(Files.readAllBytes(Paths.get(filePath)));
            
            // Pour l'instant, on fait un parsing ultra-simple "fait maison" 
            // En attendant un vrai parser JSON, on extrait les valeurs à la main ou via regex
            if (content.contains("8080")) config.ports.add(8080);
            if (content.contains("8081")) config.ports.add(8081);
            
            System.out.println("[CONFIG] Fichier de configuration chargé avec succès.");
        } catch (IOException e) {
            System.out.println("[CONFIG] Impossible de lire le fichier, utilisation de la config par défaut (Port 8080).");
            config.ports.add(8080); // Fallback de sécurité
        }
        return config;
    }
}