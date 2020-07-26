package com.sparrowwallet.sparrow.io;

import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.google.gson.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTParseException;
import com.sparrowwallet.drongo.wallet.WalletModel;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import org.controlsfx.tools.Platform;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Hwi {
    private static boolean isPromptActive = false;

    public List<Device> enumerate(String passphrase) throws ImportException {
        try {
            List<String> command;
            if(passphrase != null) {
                command = List.of(getHwiExecutable(Command.ENUMERATE).getAbsolutePath(), "--password", passphrase, Command.ENUMERATE.toString());
            } else {
                command = List.of(getHwiExecutable(Command.ENUMERATE).getAbsolutePath(), Command.ENUMERATE.toString());
            }

            String output = execute(command);
            Device[] devices = getGson().fromJson(output, Device[].class);
            return Arrays.asList(devices);
        } catch(IOException e) {
            throw new ImportException(e);
        }
    }

    public boolean promptPin(Device device) throws ImportException {
        try {
            String output = execute(getDeviceCommand(device, Command.PROMPT_PIN));
            isPromptActive = true;
            return wasSuccessful(output);
        } catch(IOException e) {
            throw new ImportException(e);
        }
    }

    public boolean sendPin(Device device, String pin) throws ImportException {
        try {
            String output = execute(getDeviceCommand(device, Command.SEND_PIN, pin));
            return wasSuccessful(output);
        } catch(IOException e) {
            throw new ImportException(e);
        }
    }

    public String getXpub(Device device, String passphrase, String derivationPath) throws ImportException {
        try {
            String output;
            if(passphrase != null && device.getModel().equals(WalletModel.TREZOR_1)) {
                output = execute(getDeviceCommand(device, passphrase, Command.GET_XPUB, derivationPath));
            } else {
                output = execute(getDeviceCommand(device, Command.GET_XPUB, derivationPath));
            }

            JsonObject result = JsonParser.parseString(output).getAsJsonObject();
            if(result.get("xpub") != null) {
                return result.get("xpub").getAsString();
            } else {
                throw new ImportException("Could not retrieve xpub");
            }
        } catch(IOException e) {
            throw new ImportException(e);
        }
    }

    public PSBT signPSBT(Device device, String passphrase, PSBT psbt) throws SignTransactionException {
        try {
            String psbtBase64 = psbt.toBase64String();

            String output;
            if(passphrase != null && device.getModel().equals(WalletModel.TREZOR_1)) {
                output = execute(getDeviceCommand(device, passphrase, Command.SIGN_TX, psbtBase64));
            } else {
                output = execute(getDeviceCommand(device, Command.SIGN_TX, psbtBase64));
            }

            JsonObject result = JsonParser.parseString(output).getAsJsonObject();
            if(result.get("psbt") != null) {
                String strPsbt = result.get("psbt").getAsString();
                return PSBT.fromString(strPsbt);
            } else {
                JsonElement error = result.get("error");
                if(error != null && error.getAsString().equals("sign_tx canceled")) {
                    throw new SignTransactionException("Signing cancelled");
                } else if(error != null) {
                    throw new SignTransactionException("Error: " + error.getAsString());
                } else {
                    throw new SignTransactionException("Could not retrieve PSBT");
                }
            }
        } catch(IOException e) {
            throw new SignTransactionException("Could not sign PSBT", e);
        } catch(PSBTParseException e) {
            throw new SignTransactionException("Could not parsed signed PSBT", e);
        }
    }

    private String execute(List<String> command) throws IOException {
        isPromptActive = false;
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();
        return CharStreams.toString(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    private synchronized File getHwiExecutable(Command command) {
        File hwiExecutable = Config.get().getHwi();
        if(hwiExecutable != null && hwiExecutable.exists()) {
            if(command.isTestFirst() && !testHwi(hwiExecutable)) {
                if(Platform.getCurrent().getPlatformId().toLowerCase().equals("mac")) {
                    deleteDirectory(hwiExecutable.getParentFile());
                } else {
                    hwiExecutable.delete();
                }
            }
        }

        if(hwiExecutable == null || !hwiExecutable.exists()) {
            try {
                Platform platform = Platform.getCurrent();
                Set<PosixFilePermission> ownerExecutableWritable = PosixFilePermissions.fromString("rwxr--r--");

                //A PyInstaller --onefile expands into a new directory on every run triggering OSX Gatekeeper checks.
                //To avoid doing these with every invocation, use a --onedir packaging and expand into a temp folder on OSX
                //The check will still happen on first invocation, but will not thereafter
                //See https://github.com/bitcoin-core/HWI/issues/327 for details
                if(platform.getPlatformId().toLowerCase().equals("mac")) {
                    InputStream inputStream = Hwi.class.getResourceAsStream("/external/" + platform.getPlatformId().toLowerCase() + "/hwi-1.1.0-mac-amd64-signed.zip");
                    Path tempHwiDirPath = Files.createTempDirectory("hwi", PosixFilePermissions.asFileAttribute(ownerExecutableWritable));
                    File tempHwiDir = tempHwiDirPath.toFile();
                    //tempHwiDir.deleteOnExit();
                    //System.out.println(tempHwiDir.getAbsolutePath());

                    File tempExec = null;
                    ZipInputStream zis = new ZipInputStream(inputStream);
                    ZipEntry zipEntry = zis.getNextEntry();
                    while (zipEntry != null) {
                        File newFile = newFile(tempHwiDir, zipEntry, ownerExecutableWritable);
                        //newFile.deleteOnExit();
                        FileOutputStream fos = new FileOutputStream(newFile);
                        ByteStreams.copy(zis, new FileOutputStream(newFile));
                        fos.flush();
                        fos.close();

                        if (zipEntry.getName().equals("hwi")) {
                            tempExec = newFile;
                        }

                        zipEntry = zis.getNextEntry();
                    }
                    zis.closeEntry();
                    zis.close();

                    hwiExecutable = tempExec;
                } else {
                    InputStream inputStream = Hwi.class.getResourceAsStream("/external/" + platform.getPlatformId().toLowerCase() + "/hwi");
                    Path tempExecPath = Files.createTempFile("hwi", null, PosixFilePermissions.asFileAttribute(ownerExecutableWritable));
                    File tempExec = tempExecPath.toFile();
                    //tempExec.deleteOnExit();
                    OutputStream tempExecStream = new BufferedOutputStream(new FileOutputStream(tempExec));
                    ByteStreams.copy(new GZIPInputStream(inputStream), tempExecStream);
                    inputStream.close();
                    tempExecStream.flush();
                    tempExecStream.close();

                    hwiExecutable = tempExec;
                }
            } catch(Exception e) {
                e.printStackTrace();
            }

            Config.get().setHwi(hwiExecutable);
        }

        return hwiExecutable;
    }

    private boolean testHwi(File hwiExecutable) {
        try {
            List<String> command = List.of(hwiExecutable.getAbsolutePath(), "enumerate");
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            Process process = processBuilder.start();
            int exitValue = process.waitFor();
            return exitValue == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }

        return directoryToBeDeleted.delete();
    }

    public static File newFile(File destinationDir, ZipEntry zipEntry, Set<PosixFilePermission> setFilePermissions) throws IOException {
        String destDirPath = destinationDir.getCanonicalPath();

        Path path = Path.of(destDirPath, zipEntry.getName());
        File destFile = Files.createFile(path, PosixFilePermissions.asFileAttribute(setFilePermissions)).toFile();

        String destFilePath = destFile.getCanonicalPath();
        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }

    private boolean wasSuccessful(String output) throws ImportException {
        JsonObject result = JsonParser.parseString(output).getAsJsonObject();
        if(result.get("error") != null) {
            throw new ImportException(result.get("error").getAsString());
        }

        return result.get("success").getAsBoolean();
    }

    private List<String> getDeviceCommand(Device device, Command command) throws IOException {
        return List.of(getHwiExecutable(command).getAbsolutePath(), "--device-path", device.getPath(), "--device-type", device.getType(), command.toString());
    }

    private List<String> getDeviceCommand(Device device, Command command, String data) throws IOException {
        return List.of(getHwiExecutable(command).getAbsolutePath(), "--device-path", device.getPath(), "--device-type", device.getType(), command.toString(), data);
    }

    private List<String> getDeviceCommand(Device device, String passphrase, Command command, String data) throws IOException {
        return List.of(getHwiExecutable(command).getAbsolutePath(), "--device-path", device.getPath(), "--device-type", device.getType(), "--password", passphrase, command.toString(), data);
    }

    public static class EnumerateService extends Service<List<Device>> {
        private final String passphrase;

        public EnumerateService(String passphrase) {
            this.passphrase = passphrase;
        }

        @Override
        protected Task<List<Device>> createTask() {
            return new Task<>() {
                protected List<Device> call() throws ImportException {
                    Hwi hwi = new Hwi();
                    return hwi.enumerate(passphrase);
                }
            };
        }
    }

    public static class ScheduledEnumerateService extends ScheduledService<List<Device>> {
        private final String passphrase;

        public ScheduledEnumerateService(String passphrase) {
            this.passphrase = passphrase;
        }

        @Override
        protected Task<List<Device>> createTask() {
            return new Task<>() {
                protected List<Device> call() throws ImportException {
                    if(!isPromptActive) {
                        Hwi hwi = new Hwi();
                        return hwi.enumerate(passphrase);
                    }

                    return null;
                }
            };
        }
    }

    public static class PromptPinService extends Service<Boolean> {
        private final Device device;

        public PromptPinService(Device device) {
            this.device = device;
        }

        @Override
        protected Task<Boolean> createTask() {
            return new Task<>() {
                protected Boolean call() throws ImportException {
                    Hwi hwi = new Hwi();
                    return hwi.promptPin(device);
                }
            };
        }
    }

    public static class SendPinService extends Service<Boolean> {
        private final Device device;
        private final String pin;

        public SendPinService(Device device, String pin) {
            this.device = device;
            this.pin = pin;
        }

        @Override
        protected Task<Boolean> createTask() {
            return new Task<>() {
                protected Boolean call() throws ImportException {
                    Hwi hwi = new Hwi();
                    return hwi.sendPin(device, pin);
                }
            };
        }
    }

    public static class GetXpubService extends Service<String> {
        private final Device device;
        private final String passphrase;
        private final String derivationPath;

        public GetXpubService(Device device, String passphrase, String derivationPath) {
            this.device = device;
            this.passphrase = passphrase;
            this.derivationPath = derivationPath;
        }

        @Override
        protected Task<String> createTask() {
            return new Task<>() {
                protected String call() throws ImportException {
                    Hwi hwi = new Hwi();
                    return hwi.getXpub(device, passphrase, derivationPath);
                }
            };
        }
    }

    public static class SignPSBTService extends Service<PSBT> {
        private final Device device;
        private final String passphrase;
        private final PSBT psbt;

        public SignPSBTService(Device device, String passphrase, PSBT psbt) {
            this.device = device;
            this.passphrase = passphrase;
            this.psbt = psbt;
        }

        @Override
        protected Task<PSBT> createTask() {
            return new Task<>() {
                protected PSBT call() throws SignTransactionException {
                    Hwi hwi = new Hwi();
                    return hwi.signPSBT(device, passphrase, psbt);
                }
            };
        }
    }

    public Gson getGson() {
        GsonBuilder gsonBuilder = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES);
        gsonBuilder.registerTypeAdapter(WalletModel.class, new DeviceModelSerializer());
        gsonBuilder.registerTypeAdapter(WalletModel.class, new DeviceModelDeserializer());
        return gsonBuilder.create();
    }

    private static class DeviceModelSerializer implements JsonSerializer<WalletModel> {
        @Override
        public JsonElement serialize(WalletModel src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.toString().toLowerCase());
        }
    }

    private static class DeviceModelDeserializer implements JsonDeserializer<WalletModel> {
        @Override
        public WalletModel deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return WalletModel.valueOf(json.getAsJsonPrimitive().getAsString().toUpperCase());
        }
    }

    private enum Command {
        ENUMERATE("enumerate", true),
        PROMPT_PIN("promptpin", true),
        SEND_PIN("sendpin", false),
        GET_XPUB("getxpub", true),
        SIGN_TX("signtx", true);

        private final String command;
        private final boolean testFirst;

        Command(String command, boolean testFirst) {
            this.command = command;
            this.testFirst = testFirst;
        }

        public String getCommand() {
            return command;
        }

        public boolean isTestFirst() {
            return testFirst;
        }

        @Override
        public String toString() {
            return command;
        }
    }
}
