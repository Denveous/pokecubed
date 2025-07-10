import java.io.*;
import java.nio.file.*;

public class Updater {
    public static void main(String[] args) {
        System.out.println("=== PokeCubed Installer Updater Started ===");
        System.out.println("Args: " + String.join(", ", args));
        
        if (args.length < 3) {
            System.out.println("ERROR: Not enough arguments");
            System.out.println("Usage: java -jar updater.jar <new_installer_path> <target_jar> <new_version>");
            System.exit(1);
        }
        
        Path newInstallerPath = Paths.get(args[0]);
        Path targetJar = Paths.get(args[1]);
        String newVersion = args[2];
        
        System.out.println("New installer path: " + newInstallerPath);
        System.out.println("Target JAR: " + targetJar);
        System.out.println("New version: " + newVersion);
        
        try {
            System.out.println("Waiting for main installer to close...");
            Thread.sleep(5000);
            
            System.out.println("Checking if new installer file exists...");
            if (!Files.exists(newInstallerPath)) {
                System.out.println("ERROR: New installer file not found: " + newInstallerPath);
                System.exit(1);
            }
            
            long newFileSize = Files.size(newInstallerPath);
            System.out.println("New installer file size: " + newFileSize + " bytes");
            
            if (newFileSize < 100000) {
                System.out.println("ERROR: New installer file seems too small, might be corrupted");
                System.exit(1);
            }
            
            Path backupFile = Paths.get(targetJar.toString() + ".backup");
            
            if (Files.exists(targetJar)) {
                System.out.println("Backing up original installer...");
                Files.move(targetJar, backupFile, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Backup created: " + backupFile);
            } else {
                System.out.println("No existing installer found at " + targetJar);
            }
            
            System.out.println("Moving new version to target location...");
            Files.move(newInstallerPath, targetJar, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Update file copy completed");
            
            System.out.println("Verifying new installer...");
            if (Files.exists(targetJar)) {
                long finalSize = Files.size(targetJar);
                System.out.println("Final installer size: " + finalSize + " bytes");
                if (finalSize == newFileSize) {
                    System.out.println("File sizes match - update successful!");
                } else {
                    System.out.println("WARNING: File sizes don't match!");
                }
            } else {
                System.out.println("ERROR: Target jar doesn't exist after move!");
                System.exit(1);
            }
            
            System.out.println("Starting new installer...");
            ProcessBuilder pb = new ProcessBuilder("java", "-jar", targetJar.toString());
            pb.directory(targetJar.getParent().toFile());
            pb.start();
            System.out.println("New installer process started");
            
            Thread.sleep(2000);
            
            if (Files.exists(backupFile)) {
                System.out.println("Cleaning up backup file...");
                Files.delete(backupFile);
            }
            
            System.out.println("=== Update completed successfully! ===");
            
        } catch (Exception e) {
            System.out.println("ERROR: Update failed: " + e.getMessage());
            e.printStackTrace();
            
            Path backupFile = Paths.get(targetJar.toString() + ".backup");
            if (Files.exists(backupFile)) {
                try {
                    System.out.println("Attempting to restore backup...");
                    Files.move(backupFile, targetJar, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Backup restored, starting original version...");
                    new ProcessBuilder("java", "-jar", targetJar.toString()).start();
                } catch (Exception restoreException) {
                    System.out.println("CRITICAL: Failed to restore backup: " + restoreException.getMessage());
                    restoreException.printStackTrace();
                }
            }
            System.exit(1);
        }
    }
}
