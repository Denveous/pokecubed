import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.io.*
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.system.exitProcess

const val INSTALLER_VERSION = "1.0.31"
const val CONFIG_URL = "https://moreno.land/dl/mpack/pokecubedinstaller.json"
const val FILE_INFO_URL = "https://moreno.land/dl/mpack/file_info.php"

private val logFile = Paths.get("PokeCubedInstaller.log")
private val timeFormatter = DateTimeFormatter.ofPattern("M/d/yyyy h:mm:ss a")

fun logMessage(message: String) {
    val timestamp = LocalDateTime.now().format(timeFormatter)
    val logEntry = "[$timestamp]: $message\n"
    try {
        Files.write(logFile, logEntry.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        println(message)
    } catch (e: Exception) {
        println("Failed to write to log: ${e.message}")
        println(message)
    }
}

@Serializable
data class ModpackConfig(
    val installer: InstallerInfo,
    val base_download: String,
    val modpack_info: ModpackInfoSimple,
    val directories: List<String>,
    val modrinth_mods: List<ModrinthMod>,
    val custom_mods: List<CustomMod>,
    val config_files: List<FileInfo>,
    val resource_packs: List<FileInfo>,
    val shader_packs: List<FileInfo>,
    val fabric_files: List<FileInfo>,
    val data_files: List<FileInfo>,
    val native_files: List<FileInfo>,
    val core_files: List<CoreFile>
)

@Serializable
data class InstallerInfo(
    val version: String,
    val download_url: String,
    val force_update: Boolean
)





@Serializable
data class ModpackInfoSimple(
    val name: String,
    val version: String,
    val minecraft_version: String,
    val loader: String,
    val loader_version: String
)

@Serializable
data class ModrinthMod(
    val project_id: String,
    val name: String,
    val version_id: String,
    val download_url: String,
    val filename: String,
    val size: Long,
    val sha1: String
)

@Serializable
data class CustomMod(
    val filename: String,
    val download_url: String,
    val target_path: String,
    val size: Long,
    val sha1: String
)

@Serializable
data class FileInfo(
    val filename: String,
    val download_url: String,
    val target_path: String,
    val size: Long,
    val sha1: String,
    val category: String
)

@Serializable
data class CoreFile(
    val filename: String,
    val download_url: String,
    val size: Long,
    val sha1: String,
    val required_for: List<String>
)

@Serializable
data class ServerFileInfo(
    val timestamp: Long,
    val files: List<ServerFile>
)

@Serializable
data class ServerFile(
    val path: String,
    val url: String,
    val size: Long,
    val modified: Long,
    val modified_iso: String
)

class ModpackInstaller(private val consoleMode: Boolean = false) : JFrame("PokéCubed Redux Modpack Installer") {
    private val json = Json { ignoreUnknownKeys = true }
    private var config: ModpackConfig? = null
    private var serverFileInfo: ServerFileInfo? = null
    private var selectedProfile = "tlauncher"
    private var minecraftDir: Path = getDefaultMinecraftDir()
    private var isInstalling = false
    
    private val profileCombo = JComboBox(arrayOf("TLauncher"))
    private val pathField = JTextField(minecraftDir.toString())
    private val browseButton = JButton("Browse")
    private val statusLabel = JLabel("Ready to install")
    private val progressBar = JProgressBar(0, 100)
    private val downloadLabel = JLabel("0/0 files")
    private val installButton = JButton("Install Modpack")
    private val removeButton = JButton("Remove Modpack")

    
    init {
        logMessage("PokéCubed Redux Modpack Installer v$INSTALLER_VERSION started")
        setupUI()
        updateInstallPath() 
        loadConfigAsync()
        checkIfInstalled()
    }
    
    private fun setupUI() {
        defaultCloseOperation = EXIT_ON_CLOSE
        try {
            val iconUrl = javaClass.getResource("/pokecubed.png")
            if (iconUrl != null) {
                val icon = ImageIcon(iconUrl)
                iconImage = icon.image
            } 
        } catch (e: Exception) {
            println("Failed to load icon: ${e.message}")
        }
        layout = BorderLayout()
        
        val mainPanel = JPanel(GridBagLayout()).apply {
            background = Color.WHITE
            border = EmptyBorder(20, 20, 20, 20)
        }
        
        val gbc = GridBagConstraints().apply {
            insets = Insets(5, 5, 5, 5)
            anchor = GridBagConstraints.WEST
        }
        
        gbc.gridx = 0; gbc.gridy = 0
        mainPanel.add(JLabel("Installation Type:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        mainPanel.add(profileCombo.apply {
            selectedIndex = 0
            addActionListener { 
                selectedProfile = "tlauncher"
                updateInstallPath()
                checkIfInstalled()
            }
        }, gbc)
        
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        mainPanel.add(JLabel("Minecraft Directory:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        mainPanel.add(pathField.apply { isEditable = false }, gbc)
        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        mainPanel.add(browseButton.apply {
            addActionListener { selectMinecraftDirectory() }
        }, gbc)
        
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        mainPanel.add(statusLabel.apply {
            font = Font(Font.SANS_SERIF, Font.BOLD, 12)
            foreground = Color(0x1976D2)
        }, gbc)
        
        gbc.gridy = 4
        mainPanel.add(progressBar.apply {
            isStringPainted = true
            string = "Ready"
        }, gbc)
        
        gbc.gridy = 5
        mainPanel.add(downloadLabel.apply {
            foreground = Color.GRAY
        }, gbc)
        
        gbc.gridy = 6; gbc.ipady = 10
        mainPanel.add(installButton.apply {
            font = Font(Font.SANS_SERIF, Font.BOLD, 14)
            background = Color(0x4CAF50)
            foreground = Color.WHITE
            isOpaque = true
            addActionListener { startInstallation() }
        }, gbc)
        
        gbc.gridy = 7; gbc.ipady = 10
        mainPanel.add(removeButton.apply {
            font = Font(Font.SANS_SERIF, Font.BOLD, 14)
            background = Color(0xF44336)
            foreground = Color.WHITE
            isOpaque = true
            addActionListener { removeModpack() }
        }, gbc)
        
        add(mainPanel, BorderLayout.CENTER)
        
        val versionLabel = JLabel("v$INSTALLER_VERSION").apply {
            font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
            foreground = Color(0x666666)
            size = preferredSize
        }
        
        mainPanel.add(versionLabel)
        mainPanel.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                versionLabel.setBounds(10, mainPanel.height - 25, versionLabel.preferredSize.width, versionLabel.preferredSize.height)
            }
        })
        
        if (consoleMode) {
            val consoleArea = JTextArea(10, 50).apply {
                isEditable = false
                background = Color.BLACK
                foreground = Color.GREEN
                font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                text = "Console mode enabled - debug output will appear here\n"
            }
            add(JScrollPane(consoleArea), BorderLayout.SOUTH)
        }
        
        pack()
        setLocationRelativeTo(null)
        isResizable = false
    }
    
    private fun getDefaultMinecraftDir(): Path {
        val os = System.getProperty("os.name").lowercase()
        val userHome = System.getProperty("user.home")
        
        return when {
            os.contains("win") -> Paths.get(System.getenv("APPDATA"), ".minecraft")
            os.contains("mac") -> Paths.get(userHome, "Library", "Application Support", "minecraft")
            else -> Paths.get(userHome, ".minecraft")
        }
    }
    
    private fun updateInstallPath() {
        val newPath = if (selectedProfile == "tlauncher") {
            minecraftDir.resolve("versions").resolve("PokeCubed")
        } else {
            minecraftDir
        }
        pathField.text = newPath.toString()
    }
    
    private fun checkIfInstalled() {
        val targetDir = if (selectedProfile == "tlauncher") {
            minecraftDir.resolve("versions").resolve("PokeCubed")
        } else {
            minecraftDir
        }
        
        val isInstalled = Files.exists(targetDir.resolve("mods")) && 
                         Files.exists(targetDir.resolve("config"))
        
        if (isInstalled) {
            CoroutineScope(Dispatchers.IO).launch {
                val hasUpdates = checkForUpdates(targetDir)
                SwingUtilities.invokeLater {
                    if (hasUpdates) {
                        installButton.text = "Update Modpack"
                        installButton.background = Color(0xFF9800)
                        statusLabel.text = "Updates available"
                    } else {
                        installButton.text = "Check for Updates"
                        installButton.background = Color(0x2196F3)
                        statusLabel.text = "Modpack up to date"
                    }
                }
            }
        } else {
            installButton.text = "Install Modpack"
            installButton.background = Color(0x4CAF50)
            statusLabel.text = "Ready to install"
        }
    }

    private fun logToConsole(message: String) {
        println(message) 
        
        SwingUtilities.invokeLater {
            val consoleArea = findConsoleArea(this)
            consoleArea?.append("$message\n")
            consoleArea?.caretPosition = consoleArea?.document?.length ?: 0
        }
    }

    private fun findConsoleArea(container: Container): JTextArea? {
        for (component in container.components) {
            when (component) {
                is JTextArea -> if (component.background == Color.BLACK) return component
                is Container -> {
                    val found = findConsoleArea(component)
                    if (found != null) return found
                }
            }
        }
        return null
    }  
    
    private fun selectMinecraftDirectory() {
        val chooser = JFileChooser(minecraftDir.toFile()).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Select Minecraft Directory"
        }
        
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            minecraftDir = chooser.selectedFile.toPath()
            updateInstallPath()
            checkIfInstalled()
        }
    }
    
    private fun removeModpack() {
        val targetDir = if (selectedProfile == "tlauncher") {
            minecraftDir.resolve("versions").resolve("PokeCubed")
        } else {
            minecraftDir.resolve("mods")
        }
        
        val result = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to remove the modpack?\nThis will delete all modpack files.",
            "Confirm Removal",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        
        if (result == JOptionPane.YES_OPTION) {
            try {
                if (Files.exists(targetDir)) {
                    targetDir.toFile().deleteRecursively()
                    statusLabel.text = "Modpack removed successfully"
                    logMessage("Modpack removed successfully")
                    checkIfInstalled()
                } else {
                    statusLabel.text = "No modpack found to remove"
                    logMessage("No modpack found to remove")
                }
            } catch (e: Exception) {
                statusLabel.text = "Failed to remove modpack"
                logMessage("Failed to remove modpack: ${e.message}")
                JOptionPane.showMessageDialog(this, "Failed to remove modpack: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
            }
        }
    }
    
    private fun loadConfigAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val configText = java.net.URI(CONFIG_URL.replace(" ", "%20")).toURL().readText()
                config = json.decodeFromString<ModpackConfig>(configText)
                
                SwingUtilities.invokeLater {
                    val modpackVersion = config?.modpack_info?.version ?: "Unknown"
                    statusLabel.text = "Ready to install v$modpackVersion"
                    
                    val currentVersion = INSTALLER_VERSION
                    val latestVersion = config?.installer?.version
                    
                    logMessage("Loaded modpack config v$modpackVersion")
                    logMessage("Installer version: $currentVersion, Latest: $latestVersion")
                    
                    checkIfInstalled()
                    
                    if (latestVersion != currentVersion && config?.installer?.force_update == true) {
                        logMessage("Installer update available: $latestVersion")
                        showInstallerUpdateDialog(latestVersion, config?.installer?.download_url)
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "Failed to load configuration"
                    installButton.isEnabled = false
                    logMessage("Failed to load config: ${e.message}")
                }
            }
        }
    }
    
    private fun checkForUpdates(targetDir: Path): Boolean {
        try {
            
            if (serverFileInfo == null) {
                try {
                    val fileInfoText = java.net.URI(FILE_INFO_URL.replace(" ", "%20")).toURL().readText()
                    serverFileInfo = json.decodeFromString<ServerFileInfo>(fileInfoText)
                } catch (e: Exception) {
                    println("Failed to load server file info for update check: ${e.message}")
                    return true
                }
            }
            
            config?.modrinth_mods?.forEach { mod ->
                val targetPath = targetDir.resolve("mods").resolve(mod.filename)
                if (shouldDownloadFile(targetPath)) return true
            }
            
            config?.custom_mods?.forEach { mod ->
                val targetPath = if (mod.target_path.isNotEmpty()) {
                    targetDir.resolve("mods").resolve(mod.target_path).resolve(mod.filename)
                } else {
                    targetDir.resolve("mods").resolve(mod.filename)
                }
                val serverFile = findServerFile("mods/${mod.filename}".replace("\\", "/"))
                if (shouldDownloadFile(targetPath, serverFile?.modified ?: 0)) return true
            }
            
            listOf(
                config?.config_files to "config",
                config?.resource_packs to "resourcepacks", 
                config?.shader_packs to "shaderpacks",
                config?.fabric_files to ".fabric",
                config?.data_files to "data",
                config?.native_files to "natives"
            ).forEach { (files, dirName) ->
                files?.forEach { file ->
                    val targetPath = if (file.target_path.isNotEmpty()) {
                        targetDir.resolve(dirName).resolve(file.target_path.replace("\\", "/")).resolve(file.filename)
                    } else {
                        targetDir.resolve(dirName).resolve(file.filename)
                    }
                    
                    val serverPath = if (file.target_path.isNotEmpty()) {
                        "$dirName/${file.target_path}/${file.filename}".replace("\\", "/")
                    } else {
                        "$dirName/${file.filename}"
                    }
                    
                    val serverFile = findServerFile(serverPath)
                    if (shouldDownloadFile(targetPath, serverFile?.modified ?: 0)) return true
                }
            }
            
            config?.core_files?.forEach { file ->
                if (file.required_for.contains(selectedProfile) || file.required_for.contains("both")) {
                    val targetPath = targetDir.resolve(file.filename)
                    val serverFile = findServerFile(file.filename)
                    if (shouldDownloadFile(targetPath, serverFile?.modified ?: 0)) return true
                }
            }
            
            return false
        } catch (e: Exception) {
            println("Error checking for updates: ${e.message}")
            return true
        }
    }
    
    private fun loadDontDeleteList(targetDir: Path): Set<String> {
        val dontDeleteFile = targetDir.resolve("dontdelete.txt")
        return if (Files.exists(dontDeleteFile)) {
            try {
                Files.readAllLines(dontDeleteFile)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toSet()
            } catch (e: Exception) {
                println("Failed to read dontdelete.txt: ${e.message}")
                emptySet()
            }
        } else {
            emptySet()
        }
    }
    
    private fun cleanupOldFiles(targetDir: Path) {
        updateStatus("Cleaning up old files...")
        
        val dontDeleteFiles = loadDontDeleteList(targetDir)
        
        // Cleanup mods
        val expectedMods = mutableSetOf<String>()
        config?.modrinth_mods?.forEach { mod ->
            expectedMods.add(mod.filename)
        }
        config?.custom_mods?.forEach { mod ->
            expectedMods.add(mod.filename)
        }
        
        val modsDir = targetDir.resolve("mods")
        if (Files.exists(modsDir)) {
            try {
                Files.walk(modsDir).use { paths ->
                    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                        .forEach { jarFile ->
                            val filename = jarFile.fileName.toString()
                            
                            if (filename !in expectedMods && filename !in dontDeleteFiles) {
                                try {
                                    if (!Files.isWritable(jarFile)) {
                                        println("Skipping read-only file: $filename")
                                        return@forEach
                                    }
                                    
                                    Files.delete(jarFile)
                                    println("Deleted old mod: $filename")
                                } catch (e: Exception) {
                                    println("Failed to delete $filename: ${e.message}")
                                }
                            }
                        }
                }
            } catch (e: Exception) {
                println("Error during mod cleanup: ${e.message}")
            }
        }
        
        // Cleanup config files
        val expectedConfigFiles = mutableSetOf<String>()
        config?.config_files?.forEach { file ->
            val fullPath = if (file.target_path.isNotEmpty()) {
                "${file.target_path}/${file.filename}"
            } else {
                file.filename
            }
            expectedConfigFiles.add(fullPath)
        }
        
        val configDir = targetDir.resolve("config")
        if (Files.exists(configDir)) {
            try {
                Files.walk(configDir).use { paths ->
                    paths.filter { Files.isRegularFile(it) }
                        .forEach { configFile ->
                            val relativePath = configDir.relativize(configFile).toString().replace("\\", "/")
                            
                            if (relativePath !in expectedConfigFiles && relativePath !in dontDeleteFiles) {
                                try {
                                    if (!Files.isWritable(configFile)) {
                                        println("Skipping read-only config file: $relativePath")
                                        return@forEach
                                    }
                                    
                                    Files.delete(configFile)
                                    println("Deleted old config file: $relativePath")
                                } catch (e: Exception) {
                                    println("Failed to delete config file $relativePath: ${e.message}")
                                }
                            }
                        }
                }
            } catch (e: Exception) {
                println("Error during config cleanup: ${e.message}")
            }
        }
    }
    
    private fun findServerFile(path: String): ServerFile? {
        val found = serverFileInfo?.files?.find { it.path == path }
        if (found == null) {
            logToConsole("Server file not found for path: $path")
            logToConsole("Available server paths: ${serverFileInfo?.files?.take(5)?.map { it.path }}")
        }
        return found
    }
    
    private fun shouldDownloadFile(filePath: Path, serverModified: Long = 0): Boolean {
        if (!Files.exists(filePath)) {
            logToConsole("File missing, will download: ${filePath.fileName}")
            return true
        }
        
        if (serverModified > 0) {
            try {
                val localModified = Files.getLastModifiedTime(filePath).toMillis() / 1000
                val needsUpdate = serverModified > localModified
                logToConsole("Checking ${filePath.fileName}: server=$serverModified, local=$localModified, needs_update=$needsUpdate")
                return needsUpdate
            } catch (e: Exception) {
                logToConsole("Failed to get local file time for ${filePath.fileName}: ${e.message}")
                return true
            }
        }
        
        logToConsole("No server timestamp for ${filePath.fileName}, skipping")
        return false
    }
    
    private fun startInstallation() {
        if (isInstalling) return
        
        if (installButton.text == "Check for Updates") {
            statusLabel.text = "Checking for updates..."
            logMessage("Checking for updates...")
            
            CoroutineScope(Dispatchers.IO).launch {
                val targetDir = if (selectedProfile == "tlauncher") {
                    minecraftDir.resolve("versions").resolve("PokeCubed")
                } else {
                    minecraftDir
                }
                
                val hasUpdates = checkForUpdates(targetDir)
                
                SwingUtilities.invokeLater {
                    if (hasUpdates) {
                        installButton.text = "Update Modpack"
                        installButton.background = Color(0xFF9800)
                        statusLabel.text = "Updates available"
                        logMessage("Updates found - ready to install")
                    } else {
                        installButton.text = "Check for Updates"
                        installButton.background = Color(0x2196F3)
                        statusLabel.text = "No updates found"
                        logMessage("No updates found - modpack is up to date")
                    }
                }
            }
            return
        }
        
        val isUpdate = installButton.text == "Update Modpack"
        
        isInstalling = true
        installButton.isEnabled = false
        removeButton.isEnabled = false
        
        logMessage("Starting ${if (isUpdate) "update" else "installation"}...")
        
        CoroutineScope(Dispatchers.IO).launch {
            val success = installModpack()
            
            SwingUtilities.invokeLater {
                isInstalling = false
                installButton.isEnabled = true
                removeButton.isEnabled = true
                
                if (success) {
                    progressBar.value = 100
                    progressBar.string = "100%"
                    
                    if (isUpdate) {
                        statusLabel.text = "Modpack updated successfully!"
                        logMessage("Modpack updated successfully")
                    } else {
                        statusLabel.text = "Modpack installed successfully!"
                        logMessage("Modpack installed successfully")
                    }
                    
                    checkIfInstalled()
                } else {
                    statusLabel.text = "${if (isUpdate) "Update" else "Installation"} failed"
                    logMessage("${if (isUpdate) "Update" else "Installation"} failed")
                }
            }
        }
    }
    
    private suspend fun installModpack(): Boolean {
        return try {
            
            updateStatus("Checking file timestamps...")
            try {
                val fileInfoText = java.net.URI(FILE_INFO_URL.replace(" ", "%20")).toURL().readText()
                serverFileInfo = json.decodeFromString<ServerFileInfo>(fileInfoText)
                println("Loaded file info for ${serverFileInfo?.files?.size ?: 0} server files")
            } catch (e: Exception) {
                println("Failed to load server file info, using fallback method: ${e.message}")
                serverFileInfo = null
            }
            
            val targetDir = if (selectedProfile == "tlauncher") {
                minecraftDir.resolve("versions").resolve("PokeCubed")
            } else {
                minecraftDir
            }
            
            updateStatus("Creating directories...")
            createDirectories(targetDir)
            
            cleanupOldFiles(targetDir)
            
            val filesToDownload = calculateFilesToDownload(targetDir)
            var downloadedFiles = 0
            
            updateStatus("Downloading mods...")
            config?.modrinth_mods?.forEach { mod ->
                val targetPath = targetDir.resolve("mods").resolve(mod.filename)
                if (shouldDownloadFile(targetPath)) {
                    updateProgress(downloadedFiles, filesToDownload, "Downloading ${mod.filename}")
                    downloadFile(mod.download_url, targetPath)
                    downloadedFiles++
                }
            }
            
            config?.custom_mods?.forEach { mod ->
                val targetPath = if (mod.target_path.isNotEmpty()) {
                    targetDir.resolve("mods").resolve(mod.target_path).resolve(mod.filename)
                } else {
                    targetDir.resolve("mods").resolve(mod.filename)
                }
                val serverFile = findServerFile("mods/${mod.filename}".replace("\\", "/"))
                if (shouldDownloadFile(targetPath, serverFile?.modified ?: 0)) {
                    updateProgress(downloadedFiles, filesToDownload, "Downloading ${mod.filename}")
                    downloadFile(mod.download_url, targetPath)
                    downloadedFiles++
                }
            }
            
            downloadedFiles = downloadFileCategoryWithTimestamp(config?.config_files, targetDir.resolve("config"), downloadedFiles, filesToDownload, "config")
            downloadedFiles = downloadFileCategoryWithTimestamp(config?.resource_packs, targetDir.resolve("resourcepacks"), downloadedFiles, filesToDownload, "resourcepacks")
            downloadedFiles = downloadFileCategoryWithTimestamp(config?.shader_packs, targetDir.resolve("shaderpacks"), downloadedFiles, filesToDownload, "shaderpacks")
            downloadedFiles = downloadFileCategoryWithTimestamp(config?.fabric_files, targetDir.resolve(".fabric"), downloadedFiles, filesToDownload, ".fabric")
            downloadedFiles = downloadFileCategoryWithTimestamp(config?.data_files, targetDir.resolve("data"), downloadedFiles, filesToDownload, "data")
            downloadedFiles = downloadFileCategoryWithTimestamp(config?.native_files, targetDir.resolve("natives"), downloadedFiles, filesToDownload, "natives")
            
            config?.core_files?.forEach { file ->
                if (file.required_for.contains(selectedProfile) || file.required_for.contains("both")) {
                    val targetPath = targetDir.resolve(file.filename)
                    val serverFile = findServerFile(file.filename)
                    if (shouldDownloadFile(targetPath, serverFile?.modified ?: 0)) {
                        updateProgress(downloadedFiles, filesToDownload, "Downloading ${file.filename}")
                        downloadFile(file.download_url, targetPath)
                        downloadedFiles++
                    }
                }
            }
            
            updateProgress(filesToDownload, filesToDownload, "Installation completed!")
            updateStatus("Installation completed!")
            true
            
        } catch (e: Exception) {
            println("Installation error: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun createDirectories(baseDir: Path) {
        config?.directories?.forEach { dir ->
            Files.createDirectories(baseDir.resolve(dir))
        }
    }
    
    private fun calculateFilesToDownload(targetDir: Path): Int {
        var count = 0
        
        config?.modrinth_mods?.forEach { mod ->
            val targetPath = targetDir.resolve("mods").resolve(mod.filename)
            if (shouldDownloadFile(targetPath)) count++
        }
        
        config?.custom_mods?.forEach { mod ->
            val targetPath = if (mod.target_path.isNotEmpty()) {
                targetDir.resolve("mods").resolve(mod.target_path).resolve(mod.filename)
            } else {
                targetDir.resolve("mods").resolve(mod.filename)
            }
            val serverFile = findServerFile("mods/${mod.filename}".replace("\\", "/"))
            if (shouldDownloadFile(targetPath, serverFile?.modified ?: 0)) count++
        }
        
        listOf(
            config?.config_files to "config",
            config?.resource_packs to "resourcepacks",
            config?.shader_packs to "shaderpacks",
            config?.fabric_files to ".fabric",
            config?.data_files to "data",
            config?.native_files to "natives"
        ).forEach { (files, dirName) ->
            files?.forEach { file ->
                val targetPath = if (file.target_path.isNotEmpty()) {
                    targetDir.resolve(dirName).resolve(file.target_path.replace("\\", "/")).resolve(file.filename)
                } else {
                    targetDir.resolve(dirName).resolve(file.filename)
                }
                
                val serverPath = if (file.target_path.isNotEmpty()) {
                    "$dirName/${file.target_path}/${file.filename}".replace("\\", "/")
                } else {
                    "$dirName/${file.filename}"
                }
                
                val serverFile = findServerFile(serverPath)
                if (shouldDownloadFile(targetPath, serverFile?.modified ?: 0)) count++
            }
        }
        
        config?.core_files?.forEach { file ->
            if (file.required_for.contains(selectedProfile) || file.required_for.contains("both")) {
                val targetPath = targetDir.resolve(file.filename)
                val serverFile = findServerFile(file.filename)
                if (shouldDownloadFile(targetPath, serverFile?.modified ?: 0)) count++
            }
        }
        
        return count
    }
    
    private suspend fun downloadFileCategoryWithTimestamp(
        files: List<FileInfo>?, 
        baseDir: Path, 
        startCount: Int, 
        totalFiles: Int, 
        category: String
    ): Int {
        var downloadedFiles = startCount
        files?.forEach { file ->
            val targetPath = if (file.target_path.isNotEmpty()) {
                baseDir.resolve(file.target_path).resolve(file.filename)
            } else {
                baseDir.resolve(file.filename)
            }
            
            val serverPath = if (file.target_path.isNotEmpty()) {
                "$category/${file.target_path}/${file.filename}".replace("\\", "/")
            } else {
                "$category/${file.filename}"
            }
            
            val serverFile = findServerFile(serverPath)
            if (shouldDownloadFile(targetPath, serverFile?.modified ?: 0)) {
                updateProgress(downloadedFiles, totalFiles, "Downloading ${file.filename}")
                downloadFile(file.download_url, targetPath)
                downloadedFiles++
            }
        }
        return downloadedFiles
    }
    
    private fun downloadFile(url: String, targetPath: Path) {
        val maxRetries = 3
        try {
            Files.createDirectories(targetPath.parent)
            
            var retryCount = 0
            
            while (retryCount < maxRetries) {
                try {
                    if (retryCount > 0) {
                        println("Retry attempt $retryCount for $url")
                        Thread.sleep(2000L * retryCount)
                    }
                    
                    val encodedUrl = url.replace(" ", "%20").replace("\\", "/")
                    val connection = java.net.URI(encodedUrl).toURL().openConnection()
                    connection.connectTimeout = 30000
                    connection.readTimeout = 60000
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    connection.setRequestProperty("Accept", "*/*")
                    connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    connection.setRequestProperty("Connection", "keep-alive")
                    
                    val contentLength = connection.contentLength
                    println("Expected file size: $contentLength bytes for ${targetPath.fileName}")
                    
                    connection.getInputStream().use { input ->
                        Files.newOutputStream(targetPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
                            val buffer = ByteArray(8192)
                            var totalBytes = 0L
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytes += bytesRead
                            }
                            output.flush()
                            println("Actually downloaded: $totalBytes bytes")
                        }
                    }
                    
                    val downloadedSize = Files.size(targetPath)
                    println("Downloaded ${targetPath.fileName}: $downloadedSize bytes")
                    
                    if (contentLength > 0 && downloadedSize != contentLength.toLong()) {
                        println("WARNING: Downloaded size ($downloadedSize) doesn't match expected size ($contentLength)")
                        if (retryCount < maxRetries - 1) {
                            println("Retrying download...")
                            retryCount++
                            continue
                        }
                    }
                    

                    
                    return
                    
                } catch (e: java.io.IOException) {
                    println("Download failed: ${e.message}")
                    if (retryCount < maxRetries - 1) {
                        retryCount++
                        continue
                    } else {
                        throw e
                    }
                }
            }
            
        } catch (e: Exception) {
            println("Failed to download $url after $maxRetries attempts: ${e.message}")
            throw e
        }
    }
    
    private fun updateStatus(status: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = status
        }
    }
    
    private fun updateProgress(current: Int, total: Int, currentFile: String) {
        SwingUtilities.invokeLater {
            val progress = if (total > 0) (current * 100) / total else 100
            progressBar.value = progress
            progressBar.string = "$progress%"
            downloadLabel.text = "$current/$total files - $currentFile"
        }
    }
    
    private fun showInstallerUpdateDialog(latestVersion: String?, downloadUrl: String?) {
        val result = JOptionPane.showConfirmDialog(
            this,
            "A new version of the installer is available.\nCurrent: $INSTALLER_VERSION\nLatest: $latestVersion\n\nDownload and install the update now?",
            "Update Available",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )
        
        if (result == JOptionPane.YES_OPTION) {
            performInstallerUpdate(downloadUrl)
        }
    }
    
    private fun performInstallerUpdate(downloadUrl: String?) {
        if (downloadUrl == null) {
            JOptionPane.showMessageDialog(this, "No download URL provided for update.", "Update Error", JOptionPane.ERROR_MESSAGE)
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SwingUtilities.invokeLater {
                    statusLabel.text = "Preparing update..."
                    progressBar.isIndeterminate = true
                    installButton.isEnabled = false
                    removeButton.isEnabled = false
                }
                
                val currentJarPath = getCurrentJarPath()
                if (currentJarPath == null) {
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(this@ModpackInstaller, "Cannot determine current installer location.", "Update Error", JOptionPane.ERROR_MESSAGE)
                        resetUIAfterUpdate()
                    }
                    return@launch
                }
                
                val updaterPath = currentJarPath.parent.resolve("PokeCubedUpdater.jar")
                val updaterUrl = "https://moreno.land/dl/mpack/jar/PokeCubedUpdater.jar"
                
                SwingUtilities.invokeLater {
                    statusLabel.text = "Downloading updater..."
                }
                println("Downloading updater from: $updaterUrl")
                downloadFile(updaterUrl, updaterPath)
                println("Updater downloaded to: $updaterPath")
                
                SwingUtilities.invokeLater {
                    statusLabel.text = "Downloading new installer..."
                }
                
                val newInstallerPath = currentJarPath.parent.resolve("PokeCubedInstaller-new.jar")
                println("Downloading new installer from: $downloadUrl")
                downloadFile(downloadUrl, newInstallerPath)
                println("New installer downloaded to: $newInstallerPath")
                
                SwingUtilities.invokeLater {
                    val result = JOptionPane.showConfirmDialog(this@ModpackInstaller,
                        "Update files downloaded successfully.\nRestart to complete the update?",
                        "Update Ready", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)
                    
                    if (result == JOptionPane.YES_OPTION) {
                        try {
                            val latestVersion = config?.installer?.version ?: "unknown"
                            println("Starting updater with args: ${newInstallerPath.toString()} $currentJarPath $latestVersion")
                            ProcessBuilder("java", "-jar", updaterPath.toString(), newInstallerPath.toString(), currentJarPath.toString(), latestVersion).start()
                            println("Updater started, exiting installer...")
                            exitProcess(0)
                        } catch (e: Exception) {
                            JOptionPane.showMessageDialog(this@ModpackInstaller,
                                "Failed to start updater: ${e.message}",
                                "Update Error", JOptionPane.ERROR_MESSAGE)
                            resetUIAfterUpdate()
                        }
                    } else {
                        resetUIAfterUpdate()
                    }
                }
                
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(this@ModpackInstaller,
                        "Failed to download update files: ${e.message}",
                        "Update Error", JOptionPane.ERROR_MESSAGE)
                    resetUIAfterUpdate()
                }
            }
        }
    }
    
    private fun getCurrentJarPath(): Path? {
        return try {
            val jarPath = ModpackInstaller::class.java.protectionDomain.codeSource.location.toURI()
            Paths.get(jarPath)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun resetUIAfterUpdate() {
        progressBar.isIndeterminate = false
        progressBar.value = 0
        statusLabel.text = "Update failed - ready to install"
        installButton.isEnabled = true
        removeButton.isEnabled = true
    }
}

fun main(args: Array<String>) {
    val consoleMode = args.contains("--console")
    
    try {
        val currentJarPath = ModpackInstaller::class.java.protectionDomain.codeSource.location.toURI()
        val currentDir = Paths.get(currentJarPath).parent
        val updaterPath = currentDir.resolve("PokeCubedUpdater.jar")
        
        if (Files.exists(updaterPath)) {
            Thread.sleep(1000)
            Files.deleteIfExists(updaterPath)
            logMessage("Cleaned up updater file")
        }
    } catch (e: Exception) {
        logMessage("Failed to cleanup updater: ${e.message}")
    }
    
    if (consoleMode) {
        logMessage("Console mode enabled")
    }
    
    SwingUtilities.invokeLater {
        ModpackInstaller(consoleMode).isVisible = true
    }
}
