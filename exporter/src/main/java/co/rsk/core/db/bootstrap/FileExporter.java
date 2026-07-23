/*
 * This file is part of RskJ
 * Copyright (C) 2019 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package co.rsk.core.db.bootstrap;

import co.rsk.RskContext;
import co.rsk.core.BlockDifficulty;
import co.rsk.db.importer.BootstrapV2Format;
import co.rsk.trie.IterationElement;
import co.rsk.trie.Trie;
import co.rsk.trie.TrieStore;
import org.ethereum.core.Block;
import org.ethereum.db.BlockStore;
import org.ethereum.util.RLP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports the bootstrap data ({@code blocks} + {@code state}) of an RSK database into the
 * {@code bootstrap-data.bin} <b>v2</b> format (wrapped in a zip).
 *
 * <p>The v2 format ({@link BootstrapV2Format}) is self-describing, chunked and streamable, with no
 * total-size ceiling - it replaces the legacy single-nested-RLP-list format whose {@code int} length
 * fields capped the payload at {@code Integer.MAX_VALUE} (~2 GiB), which testnet state has outgrown.
 *
 * <p>This exporter is <b>streaming and bounded in memory</b> so it can serialize arbitrarily large
 * state without OOMing:
 * <ul>
 *   <li>The trie is walked with a <b>non-caching</b> in-order iterator
 *       ({@code getInOrderIterator(false)}), so visited nodes are not memoized back into the held
 *       root and the retained set stays bounded to the active path.</li>
 *   <li>The {@code nodes} and {@code values} element streams are spilled to temp files in a single
 *       traversal (tracking only a small list of chunk boundaries), then copied through into the zip
 *       as the section's chunks. Heap stays bounded to the active trie path.</li>
 * </ul>
 *
 * <p>The per-element (leaf) encoding is unchanged from v1: each node is {@code RLP.encodeElement(
 * trie.toMessage())}, each long value {@code RLP.encodeElement(value)}, each block
 * {@code LIST[ELEMENT(block), ELEMENT(td)]}. Only the container framing around the elements changed.
 */
public class FileExporter {

    private static final Logger logger = LoggerFactory.getLogger("exporter");

    private static final int BLOCKS_NEEDED = 4000;

    private static final String SYS_PROP_OUTPUT = "exporter.output";
    private static final String CMD_ARG_OUTPUT = "-exporter-output";

    private static final String BOOTSTRAP_DATA_ZIP_NAME = "bootstrap-data.zip";
    private static final String BOOTSTRAP_DATA_FILE_NAME = "bootstrap-data.bin";

    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    public static void main(String[] args) throws IOException {
        final List<String> argList = new ArrayList<>(Arrays.asList(args));

        // first arg should always be a number of the last block of a range to process
        final long to = Long.parseLong(argList.get(0));
        final long from = to - BLOCKS_NEEDED;
        // remove the first arg, as there's no need to propagate it as part of rsk args
        argList.remove(0);

        final int destFolderKeyIndex = argList.indexOf(CMD_ARG_OUTPUT);
        final String destinationFolder;
        if (destFolderKeyIndex != -1) {
            // remove the destination arg key and value, as there's no need to propagate them as part of rsk args
            destinationFolder = argList.remove(destFolderKeyIndex + 1);
            argList.remove(destFolderKeyIndex);
        } else {
            destinationFolder = System.getProperty(SYS_PROP_OUTPUT, "./output");
        }

        final String[] rskArgs = argList.toArray(new String[0]);
        final RskContext rskContext = new RskContext(rskArgs);

        logger.info("Database folder: {}", rskContext.getRskSystemProperties().databaseDir());
        logger.info("Destination folder: {}", destinationFolder);

        final BlockStore originBlockStore = rskContext.getBlockStore();
        final TrieStore originTrieStore = rskContext.getTrieStore();

        final Block bestBlock = Objects.requireNonNull(originBlockStore.getChainBlockByNumber(to));

        final long start = System.currentTimeMillis();
        writeBootstrapData(originBlockStore, originTrieStore, bestBlock, from, to, new File(destinationFolder));
        final long totalDuration = System.currentTimeMillis() - start;
        logger.info("Total duration: {} ms.", totalDuration);

        System.exit(0);
    }

    private static void writeBootstrapData(BlockStore originBlockStore,
                                           TrieStore originTrieStore,
                                           Block bestBlock,
                                           long from,
                                           long to,
                                           File destinationFolder) throws IOException {
        File blocksTmp = null;
        File nodesTmp = null;
        File valuesTmp = null;
        try {
            // the destination (e.g. the default ./output) may not exist yet; create it before writing into it
            Files.createDirectories(destinationFolder.toPath());
            blocksTmp = File.createTempFile("bootstrap-blocks", ".tmp", destinationFolder);
            nodesTmp = File.createTempFile("bootstrap-nodes", ".tmp", destinationFolder);
            valuesTmp = File.createTempFile("bootstrap-values", ".tmp", destinationFolder);

            // blocks are a bounded range (BLOCKS_NEEDED), but spilled through the same chunked path for uniformity
            final List<Long> blockChunks = spillBlocks(originBlockStore, from, to, blocksTmp);

            logger.info("Encoding state from block {} {}", bestBlock.getNumber(), bestBlock.getStateRoot());

            // state is the unbounded part: spill its node/value elements to temp files in a single,
            // non-caching traversal so neither the trie graph nor the element lists are retained
            final StateSpill state = streamStateElements(originTrieStore, bestBlock.getStateRoot(), nodesTmp, valuesTmp);

            try (OutputStream fos = new BufferedOutputStream(
                    new FileOutputStream(new File(destinationFolder, BOOTSTRAP_DATA_ZIP_NAME)), COPY_BUFFER_SIZE);
                 ZipOutputStream zipOut = new ZipOutputStream(fos)) {
                zipOut.putNextEntry(new ZipEntry(BOOTSTRAP_DATA_FILE_NAME));
                writeBin(zipOut, blocksTmp, blockChunks, valuesTmp, nodesTmp, state);
                zipOut.closeEntry();
            }
        } finally {
            deleteQuietly(blocksTmp);
            deleteQuietly(nodesTmp);
            deleteQuietly(valuesTmp);
        }
    }

    /** Best-effort temp-file cleanup: null-safe and never throws, so it cannot mask the primary export error. */
    private static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            logger.warn("Could not delete temp file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Writes the full v2 {@code bootstrap-data.bin} payload to {@code out}: the header (magic + version),
     * then the blocks, values and nodes sections, then the end marker. Values are emitted <b>before</b> the
     * nodes that reference them, so the importer applies them in a single streaming pass (values into the
     * destination store, then nodes resolve them at save time) without a value-staging store or a second scan.
     *
     * <p>The caller owns the container: production wraps a {@link ZipOutputStream}, the round-trip test a
     * plain stream. Keeping the framing here means the on-disk section order lives in exactly one place.
     */
    static void writeBin(OutputStream out, File blocksTmp, List<Long> blockChunks,
                         File valuesTmp, File nodesTmp, StateSpill state) throws IOException {
        out.write(BootstrapV2Format.magic());
        out.write(BootstrapV2Format.VERSION);
        writeSection(out, BootstrapV2Format.TAG_BLOCKS, blocksTmp, blockChunks);
        writeSection(out, BootstrapV2Format.TAG_VALUES, valuesTmp, state.valueChunkLengths);
        writeSection(out, BootstrapV2Format.TAG_NODES, nodesTmp, state.nodeChunkLengths);
        out.write(BootstrapV2Format.TAG_END);
    }

    /** Spills each block tuple {@code LIST[ELEMENT(block), ELEMENT(td)]} as a chunked element stream. */
    static List<Long> spillBlocks(BlockStore originBlockStore, long from, long to, File blocksFile) throws IOException {
        logger.info("Encoding blocks from {} to {}", from, to);
        try (SectionSpill spill = new SectionSpill(blocksFile)) {
            for (long blockNumber = from + 1; blockNumber <= to; blockNumber++) {
                final Block block = originBlockStore.getChainBlockByNumber(blockNumber);
                final BlockDifficulty totalDifficulty = originBlockStore.getTotalDifficultyForHash(block.getHash().getBytes());

                final byte[] tuple = RLP.encodeList(
                        RLP.encodeElement(block.getEncoded()),
                        RLP.encodeElement(totalDifficulty.getBytes()));
                spill.write(tuple);
            }
            logger.info("Blocks processing completed");
            return spill.finish();
        }
    }

    /**
     * Walks the state trie once (non-caching), spilling the RLP node elements to {@code nodesFile} and
     * the RLP long-value elements to {@code valuesFile}, returning the chunk-boundary lengths of each.
     * The emitted element sequence is identical to the legacy implementation (root node element first,
     * then in-order traversal), so the leaf encoding is unchanged - only the chunked framing is new.
     */
    static StateSpill streamStateElements(TrieStore originTrieStore,
                                          byte[] stateRoot,
                                          File nodesFile,
                                          File valuesFile) throws IOException {
        Trie node = originTrieStore.retrieve(stateRoot).orElseThrow(NullPointerException::new);

        int nodeCount = 0;

        try (SectionSpill nodesSpill = new SectionSpill(nodesFile);
             SectionSpill valuesSpill = new SectionSpill(valuesFile)) {

            // root element first; the explicit write guarantees the root is emitted even if embeddable
            nodesSpill.write(RLP.encodeElement(node.toMessage()));
            nodeCount++;

            // non-caching iterator: visited nodes are not memoized into the held root, so the retained
            // set stays bounded to the active path instead of pinning the whole visited trie
            Iterator<IterationElement> it = node.getInOrderIterator(false);

            logger.info("Trie processing...");
            while (it.hasNext()) {
                Trie iterating = it.next().getNode();
                if (iterating.hasLongValue()) {
                    valuesSpill.write(RLP.encodeElement(iterating.getValue()));
                }
                if (iterating.isEmbeddable()) {
                    continue;
                }
                nodesSpill.write(RLP.encodeElement(iterating.toMessage()));
                nodeCount++;

                if (nodeCount % 10_000 == 0) {
                    logger.info("Processed {} nodes", nodeCount);
                }
            }

            StateSpill result = new StateSpill(nodesSpill.finish(), valuesSpill.finish());
            logger.info("Processed {} nodes", nodeCount);
            logger.info("Trie processing completed");
            return result;
        }
    }

    /**
     * Writes a tagged section to {@code out}: the 1-byte tag, then each chunk as
     * {@code [8-byte BE length][bytes]} copied from {@code tmp}, then the 8-byte zero terminator.
     */
    static void writeSection(OutputStream out, int tag, File tmp, List<Long> chunkLengths) throws IOException {
        out.write(tag);
        // 8-byte big-endian length prefixes, mirroring the reader's DataInputStream.readLong()
        DataOutputStream dataOut = new DataOutputStream(out);
        try (InputStream in = Files.newInputStream(tmp.toPath())) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            for (long chunkLength : chunkLengths) {
                dataOut.writeLong(chunkLength);
                copyExactly(in, dataOut, chunkLength, buffer);
            }
        }
        dataOut.writeLong(0L); // end-of-section sentinel
    }

    private static void copyExactly(InputStream in, OutputStream out, long count, byte[] buffer) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, toRead);
            if (read < 0) {
                throw new IOException("Unexpected end of spill file: missing " + remaining + " bytes");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    /** Chunk-boundary lengths for the two (large) state sections. */
    static final class StateSpill {
        final List<Long> nodeChunkLengths;
        final List<Long> valueChunkLengths;

        StateSpill(List<Long> nodeChunkLengths, List<Long> valueChunkLengths) {
            this.nodeChunkLengths = nodeChunkLengths;
            this.valueChunkLengths = valueChunkLengths;
        }
    }

    /**
     * Spills a stream of whole RLP elements to a temp file, recording where chunk boundaries fall: a
     * chunk is closed (on an element boundary) once it would cross the configurable {@code chunkMax}
     * (which defaults to {@link BootstrapV2Format#CHUNK_MAX}; tests may pass a smaller cap). A single
     * element larger than {@code chunkMax} becomes its own (oversized) chunk. Only the small list of
     * chunk lengths is kept in memory - the elements themselves stream to disk.
     */
    static final class SectionSpill implements Closeable {
        private final OutputStream out;
        private final long chunkMax;
        private final List<Long> chunkLengths = new ArrayList<>();
        private long currentChunkLength;

        SectionSpill(File file) throws IOException {
            this(file, BootstrapV2Format.CHUNK_MAX);
        }

        SectionSpill(File file, long chunkMax) throws IOException {
            this.out = new BufferedOutputStream(new FileOutputStream(file), COPY_BUFFER_SIZE);
            this.chunkMax = chunkMax;
        }

        void write(byte[] element) throws IOException {
            if (currentChunkLength > 0 && currentChunkLength + element.length > chunkMax) {
                chunkLengths.add(currentChunkLength);
                currentChunkLength = 0;
            }
            out.write(element);
            currentChunkLength += element.length;
        }

        List<Long> finish() throws IOException {
            if (currentChunkLength > 0) {
                chunkLengths.add(currentChunkLength);
            }
            out.flush();
            return chunkLengths;
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }
}
