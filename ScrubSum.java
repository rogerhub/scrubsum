import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Scanner;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The interactive file scrubber.
 *
 * It is recommended that you have a wrapper script to run this
 * file. For an example:
 *
 *     #!/bin/bash
 *     BASE_DIR=$(dirname "$0")
 *     java -cp "$BASE_DIR" ScrubSum $*
 *
 * Name this 'scrubsum', and put it on your path.
 */

public class ScrubSum {

    public static final String USAGE =
            "Usage: scrubsum [--verify-only] [--commit-changes] target_directory";
    public static final String SUMS_FILE_NAME = "SCRUBSUMS";

    private static final String FLAG_VERIFY_ONLY = "--verify-only";
    private static final String FLAG_COMMIT_CHANGES = "--commit-changes";
    private static final String FLAG_HELP = "--help";

    public static final int EXIT_NO_CHANGE = 0;
    public static final int EXIT_YES_CHANGE = 1;
    public static final int EXIT_ENOENT = 2;
    public static final int EXIT_EIO = 5;
    public static final int EXIT_EACCES = 13;
    public static final int EXIT_ENOTDIR = 20;
    public static final int EXIT_EINVAL = 22;

    public static final int WORKERS = 40;

    private static Pattern sumPattern = Pattern.compile(
            "^(?<sum>[0-9a-f]{40})  (?<name>.*)$", Pattern.DOTALL);

    private String scrubPath;
    private Map<String, String> scrubSums;
    private Map<String, String> newScrubSums;

    public static void main(String[] args) {

        Boolean commitChanges = null;
        String targetDirectory = null;

        for (String arg : args) {
            if (arg.equals(FLAG_HELP)) {
                System.err.println(USAGE);
                System.exit(EXIT_EINVAL);
            } else if (arg.equals(FLAG_VERIFY_ONLY)) {
                if (commitChanges != null) {
                    System.err.println("Cannot specify both " + FLAG_VERIFY_ONLY + " and " +
                            FLAG_COMMIT_CHANGES);
                    System.exit(EXIT_EINVAL);
                }
                commitChanges = false;
            } else if (arg.equals(FLAG_COMMIT_CHANGES)) {
                if (commitChanges != null) {
                    System.err.println("Cannot specify both " + FLAG_VERIFY_ONLY + " and " +
                            FLAG_COMMIT_CHANGES);
                    System.exit(EXIT_EINVAL);
                }
                commitChanges = true;
            } else {
                if (targetDirectory == null) {
                    targetDirectory = arg;
                } else {
                    System.err.println("scrub: Unrecognized option: " + arg);
                    System.exit(EXIT_EINVAL);
                }
            }
        }

        /* If we're not listening to a TTY, never commit changes (unless asked to). */
        if (commitChanges == null && System.console() == null) {
            commitChanges = false;
        }

        if (targetDirectory == null) {
            System.err.println(USAGE);
            System.exit(EXIT_EINVAL);
        }

        File scrubPathFile = new File(targetDirectory);
        ScrubSum scrub = new ScrubSum(scrubPathFile.getAbsolutePath());

        /* The ordering here is (somewhat) important. */
        scrub.computeChangedFiles();
        List<String> modifiedFiles = scrub.getModifiedFiles();
        List<String> deletedFiles = scrub.getDeletedFiles();

        scrub.computeAddedFiles();
        List<String> addedFiles = scrub.getAddedFiles();

        scrub.reportTotalProgress();

        scrub.printFileList(modifiedFiles, " M ");
        scrub.printFileList(deletedFiles, " D ");
        scrub.printFileList(addedFiles, " A ");

        scrub.printSummary(modifiedFiles, deletedFiles, addedFiles);

        if (modifiedFiles.size() == 0 &&
                deletedFiles.size() == 0 &&
                addedFiles.size() == 0) {
            System.exit(EXIT_NO_CHANGE);
        }

        if (commitChanges == null) {

            try (BufferedReader keyboard = new BufferedReader(new InputStreamReader(
                            System.in))) {
                while (true) {
                    System.err.print("Accept these changes [yn]? ");
                    System.err.flush();
                    String reply = "n";
                    try {
                        reply = keyboard.readLine();
                    } catch (IOException e) {
                        System.err.println("scrub: Error reading respnose. Exiting..");
                        System.exit(EXIT_EIO);
                    }
                    if (reply == null) {
                        /* EOF */
                        System.err.println();
                        break;
                    } else if (reply.equals("y")) {
                        commitChanges = true;
                        break;
                    } else if (reply.equals("n")) {
                        commitChanges = false;
                        break;
                    } else {
                        System.err.println("Please choose either 'y' or 'n'.");
                    }
                }
            } catch (IOException e) {
                System.exit(EXIT_EIO);
            }

        }

        if (commitChanges) {
            scrub.writeSums();
            System.err.println("SCRUBSUMS updated.");
        } else {
            System.err.println("Exiting without changes...");
        }
        System.exit(EXIT_YES_CHANGE);

    }

    public ScrubSum(String path) {
        scrubPath = path;
        checkScrubPath();
        readSums();
        newScrubSums = new ConcurrentHashMap<String, String>(scrubSums);
    }

    public void checkScrubPath() {
        File f = new File(scrubPath);
        if (!f.exists()) {
            System.err.println("scrub: no such directory");
            System.exit(EXIT_ENOENT);
        } else if (!f.isDirectory()) {
            System.err.println("scrub: not a directory");
            System.exit(EXIT_ENOTDIR);
        }
    }

    public void readSums() {
        File sumsFile = new File(scrubPath, SUMS_FILE_NAME);
        scrubSums = new HashMap<String, String>();
        if (!sumsFile.exists() || !sumsFile.isFile()) {
            return;
        }
        if (!sumsFile.isFile() || !sumsFile.canRead()) {
            System.err.println("scrub: permission denied on " + sumsFile.getAbsolutePath());
            System.exit(EXIT_EACCES);
        }

        try (Scanner sumsReader = new Scanner(sumsFile)) {
            /* Compatibility with shasums. */
            sumsReader.useDelimiter("\n");
            while (sumsReader.hasNext()) {
                String sumLine = sumsReader.next();
                if (sumLine == null || sumLine.isEmpty()) {
                    return;
                }

                /* Stupid hack, inherited from shasum. */
                boolean escapedFileName = false;
                String realSumLine = sumLine;
                if (realSumLine.charAt(0) == '\\') {
                    realSumLine = realSumLine.substring(1);
                    escapedFileName = true;
                }

                Matcher sumMatcher = sumPattern.matcher(realSumLine);
                if (!sumMatcher.matches()) {
                    System.err.println("scrub: ignored line: " + sumLine);
                    continue;
                }

                String fileName = sumMatcher.group("name");
                String fileSum = sumMatcher.group("sum");

                if (escapedFileName) {
                    fileName = unescapeFileName(fileName);
                }

                scrubSums.put(fileName, fileSum);
            }
        } catch (IOException e) {
            return;
        }
    }

    public void writeSums() {
        File sumsFile = new File(scrubPath, SUMS_FILE_NAME);
        if (sumsFile.exists() && (!sumsFile.isFile() || !sumsFile.canWrite())) {
            System.err.println("scrub: permission denied on " + sumsFile.getAbsolutePath());
            System.exit(EXIT_EACCES);
        }
        try (FileWriter sumsWriter = new FileWriter(sumsFile)) {
            for (String fileName : newScrubSums.keySet()) {
                String fileSum = newScrubSums.get(fileName);
                if (needsToBeEscaped(fileName)) {
                    /* Stupid hack, inherited from shasum. */
                    sumsWriter.write("\\");
                    fileName = escapeFileName(fileName);
                }
                sumsWriter.write(fileSum + "  " + fileName + "\n");
            }
            sumsWriter.close(); /* Flush and close. */
        } catch (IOException e) {
            System.err.println("scrub: Unexpected IOException: " + e);
            System.exit(EXIT_EIO);
        }
    }

    /* Stupid hack, inherited from shasum. */

    public String escapeFileName(String fileName) {
        return fileName.replace("\\", "\\\\")
                .replace("\n", "\\n");
    }

    public String unescapeFileName(String fileName) {
        return fileName.replace("\\\\", "\0")
                .replace("\\n", "\n")
                .replace("\0", "\\");
    }

    public boolean needsToBeEscaped(String fileName) {
        return fileName.indexOf("\n") != -1;
    }

    private List<String> modifiedList = null;
    private List<String> deletedList = null;

    public List<String> getModifiedFiles() {
        return modifiedList;
    }

    public List<String> getDeletedFiles() {
        return deletedList;
    }

    public void computeChangedFiles() {
        modifiedList = new ArrayList<String>();
        deletedList = new ArrayList<String>();

        Queue<String> filesQueue = new ConcurrentLinkedQueue<String>();
        for (String fileName : scrubSums.keySet()) {
            filesQueue.add(fileName);
        }

        Thread[] workers = new Thread[WORKERS];
        for (int i = 0; i < WORKERS; i++) {
            workers[i] = new ChangedFilesWorker(filesQueue);
            workers[i].start();
        }

        for (int i = 0; i < WORKERS; i++) {
            try {
                workers[i].join();
            } catch (InterruptedException e) {
                continue;
            }
        }
    }

    private class ChangedFilesWorker extends Thread {

        private Queue<String> filesQueue;

        public ChangedFilesWorker(Queue<String> filesQueue) {
            this.filesQueue = filesQueue;
        }

        @Override
        public void run() {
            String fileName, expectedSum, actualSum;
            while (true) {
                fileName = filesQueue.poll();
                if (fileName == null) {
                    break;
                }
                expectedSum = scrubSums.get(fileName);
                actualSum = computeSum(fileName);
                if (actualSum.equals("")) {
                    synchronized (deletedList) {
                        deletedList.add(fileName);
                    }
                    newScrubSums.remove(fileName);
                } else if (!expectedSum.equals(actualSum)) {
                    synchronized (modifiedList) {
                        modifiedList.add(fileName);
                    }
                    newScrubSums.put(fileName, actualSum);
                }
            }
        }
    }

    private List<String> addedList = null;

    public List<String> getAddedFiles() {
        return addedList;
    }

    public void computeAddedFiles() {
        addedList = new ArrayList<String>();
        addAddedFiles(scrubPath);

        Queue<String> filesQueue = new ConcurrentLinkedQueue<String>(addedList);

        Thread[] workers = new Thread[WORKERS];
        for (int i = 0; i < WORKERS; i++) {
            workers[i] = new AddedFilesWorker(filesQueue);
            workers[i].start();
        }

        for (int i = 0; i < WORKERS; i++) {
            try {
                workers[i].join();
            } catch (InterruptedException e) {
                continue;
            }
        }
    }

    private void addAddedFiles(String path) {
        File pathFile = new File(path);
        File[] pathChildren = pathFile.listFiles();

        if (pathChildren == null) {
            System.err.println("scrub: permission denied on " + path);
            System.exit(EXIT_EACCES);
        }

        for (File childFile : pathChildren) {
            if (childFile.isDirectory()) {
                addAddedFiles(childFile.getAbsolutePath());
            } else {
                String absolutePath = childFile.getAbsolutePath();
                String relativePath = getRelativePath(absolutePath);
                if (relativePath.equals(SUMS_FILE_NAME)) {
                    continue;
                }
                if (!scrubSums.containsKey(relativePath)) {
                    addedList.add(relativePath);
                }
            }
        }
    }

    private class AddedFilesWorker extends Thread {

        private Queue<String> filesQueue;

        public AddedFilesWorker(Queue<String> filesQueue) {
            this.filesQueue = filesQueue;
        }

        @Override
        public void run() {
            String fileName, fileSum;
            while (true) {
                fileName = filesQueue.poll();
                if (fileName == null) {
                    break;
                }
                fileSum = computeSum(fileName);
                newScrubSums.put(fileName, fileSum);
            }
        }

    }

    private static Path scrubPathPath = null;
    public String getRelativePath(String path) {
        if (scrubPathPath == null) {
            scrubPathPath = Paths.get(scrubPath);
        }
        return scrubPathPath.relativize(Paths.get(path)).toString();
    }

    public void printFileList(List<String> fileNames, String prefix) {
        for (String fileName : fileNames) {
            if (needsToBeEscaped(fileName)) {
                /* Stupid hack, inherited from shasum. */
                fileName = escapeFileName(fileName);
            }
            System.out.println(prefix + fileName);
        }
        System.out.flush();
    }

    public void printSummary(List<String> modifiedFiles,
            List<String> deletedFiles, List<String> addedFiles) {

        if (modifiedFiles.size() > 0) {
            System.err.print(modifiedFiles.size() + " files modified. ");
        }

        if (deletedFiles.size() > 0) {
            System.err.print(deletedFiles.size() + " files deleted. ");
        }

        if (addedFiles.size() > 0) {
            System.err.print(addedFiles.size() + " files added. ");
        }

        if (modifiedFiles.size() == 0 &&
                deletedFiles.size() == 0 &&
                addedFiles.size() == 0) {
            System.err.print("No changes detected.");
        }

        System.err.println();
        System.err.flush();
    }

    public static final byte[] HEX_CHAR_TABLE = {
        (byte)'0', (byte)'1', (byte)'2', (byte)'3',
        (byte)'4', (byte)'5', (byte)'6', (byte)'7',
        (byte)'8', (byte)'9', (byte)'a', (byte)'b',
        (byte)'c', (byte)'d', (byte)'e', (byte)'f'
    };

    private AtomicInteger sumCount = new AtomicInteger();

    private void reportProgress() {
        int sumCount = this.sumCount.addAndGet(1);

        if (sumCount < 10) {
            return;
        }

        int currentSumCount = sumCount;
        int previousSumCount = currentSumCount - 1;

        final int BASE = 10;

        while (currentSumCount > 10) {
            currentSumCount /= 10;
            previousSumCount /= 10;
        }

        if (previousSumCount != currentSumCount) {
            System.err.println("scrub: " + sumCount + " files");
            System.err.flush();
        }

    }

    private void reportTotalProgress() {
        int sumCount = this.sumCount.get();
        System.err.println("scrub: " + sumCount + " files");
    }

    public String computeSum(String fileName) {
        File file = new File(scrubPath, fileName);
        if (!file.exists() || !file.isFile()) {
            return "";
        }
        if (!file.canRead()) {
            System.err.println("scrub: permission denied on " + file.getAbsolutePath());
            System.exit(EXIT_EACCES);
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            DigestInputStream inputStream = new DigestInputStream(
                    new FileInputStream(file.getAbsolutePath()), digest);

            byte[] discardBuffer = new byte[4 * 1024 * 1024];
            int bytesRead;
            do {
                bytesRead = inputStream.read(discardBuffer, 0, discardBuffer.length);
            } while (bytesRead > 0);

            inputStream.close();

            byte[] binarySum = digest.digest();
            byte[] hexadecimalSum = new byte[binarySum.length * 2];
            int index = 0;

            for (byte b : binarySum) {
                int v = b & 0xFF;
                hexadecimalSum[index++] = HEX_CHAR_TABLE[v >>> 4];
                hexadecimalSum[index++] = HEX_CHAR_TABLE[v & 0xF];
            }

            reportProgress();

            try {
                return new String(hexadecimalSum, "ASCII");
            } catch (UnsupportedEncodingException e) {
                System.err.println("scrub: ASCII encoding not supported (what?)");
                System.exit(EXIT_EIO);
                return "";
            }
        } catch (IOException e) {
            return "";
        } catch (NoSuchAlgorithmException e) {
            System.err.println("scrub: SHA-1 algorithm not found");
            System.exit(EXIT_EIO);
            return "";
        }
    }

}
