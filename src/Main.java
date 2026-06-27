public class Main {
    public static void main(String[] args) {
        // Charge la configuration via ConfigLoader
        ConfigLoader.ServerConfig config = ConfigLoader.loadConfig("config.json");
        Server server = new Server(config);
        server.start();
    }
}