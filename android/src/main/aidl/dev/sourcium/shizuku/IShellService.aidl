package dev.sourcium.shizuku;

interface IShellService {
    void destroy() = 16777114; // Required by Shizuku

    /** Execute a shell command and return the output. */
    String exec(String command) = 1;

    /** Execute a shell command with stdin from a file descriptor and return the output. */
    String execWithStdin(String command, in ParcelFileDescriptor fd) = 2;
}
