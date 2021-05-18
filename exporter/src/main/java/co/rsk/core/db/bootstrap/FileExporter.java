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
import co.rsk.trie.Trie;
import co.rsk.trie.TrieStore;
import org.ethereum.core.Block;
import org.ethereum.db.BlockStore;
import org.ethereum.util.RLP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FileExporter{

    private static final Logger logger = LoggerFactory.getLogger("exporter");

    private static final int BLOCKS_NEEDED = 4000;

    private static final String SYS_PROP_OUTPUT = "exporter.output";
    private static final String CMD_ARG_OUTPUT = "-exporter-output";

    private static final String BOOTSTRAP_DATA_ZIP_NAME = "bootstrap-data.zip";
    private static final String BOOTSTRAP_DATA_FILE_NAME = "bootstrap-data.bin";

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
        final byte[] blocks = encodeBlocks(originBlockStore, from, to);
        final byte[] state = encodeState(originTrieStore, bestBlock);
        final byte[] bootstrap = RLP.encodeList(RLP.encodeElement(blocks), RLP.encodeElement(state));

        final long encodingDuration = System.currentTimeMillis() - start;

        FileOutputStream fos = new FileOutputStream(new File(destinationFolder, BOOTSTRAP_DATA_ZIP_NAME));
        ZipOutputStream zipOut = new ZipOutputStream(fos);

        zipFile(new ByteArrayInputStream(bootstrap), zipOut);
        zipOut.close();

        final long totalDuration = System.currentTimeMillis() - start;
        logger.info("Encoding duration: " + encodingDuration + " ms; Total duration: " + totalDuration + " ms.");

        System.exit(0);
    }

    private static byte[] encodeBlocks(BlockStore originBlockStore, long from, long to) {
        logger.info("Encoding blocks from {} to {}", from, to);
        byte[][] encodedBlocks = new byte[BLOCKS_NEEDED][];

        logger.info("Blocks processing...");
        for(int i = 0; i < to - from; i++) {
            final long blockNumber = from + (i + 1);

            final Block block = originBlockStore.getChainBlockByNumber(blockNumber);
            final BlockDifficulty totalDifficulty = originBlockStore.getTotalDifficultyForHash(block.getHash().getBytes());

            final byte[][] encodedTuple = new byte[2][];
            encodedTuple[0] = RLP.encodeElement(block.getEncoded());
            encodedTuple[1] = RLP.encodeElement(totalDifficulty.getBytes());

            encodedBlocks[i] = RLP.encodeList(encodedTuple);
        }
        logger.info("Blocks processing completed");

        return RLP.encodeList(encodedBlocks);
    }

    private static byte[] encodeState(TrieStore originTrieStore, Block block) {
        Trie node = originTrieStore.retrieve(block.getStateRoot()).orElseThrow(NullPointerException::new);
        logger.info("Encoding state from block {} {}", block.getNumber(), node.getHash());

        List<byte[]> encodedNodes = new ArrayList<>();
        List<byte[]> encodedValues = new ArrayList<>();
        byte[] nodeBytes = node.toMessage();
        encodedNodes.add(RLP.encodeElement(nodeBytes));
        Iterator<Trie.IterationElement> it = node.getInOrderIterator();

        logger.info("Trie processing...");
        while (it.hasNext()) {
            Trie iterating = it.next().getNode();
            if (iterating.hasLongValue()) {
                encodedValues.add(RLP.encodeElement(iterating.getValue()));
            }
            if (iterating.isEmbeddable()) {
                continue;
            }
            nodeBytes = iterating.toMessage();
            encodedNodes.add(RLP.encodeElement(nodeBytes));

            if (encodedNodes.size() % 10_000 == 0) {
                logger.info("Processed {} nodes", encodedNodes.size());
            }
        }
        logger.info("Processed {} nodes", encodedNodes.size());
        logger.info("Trie processing completed");

        return RLP.encodeList(
                RLP.encodeList(encodedNodes.toArray(new byte[encodedNodes.size()][])),
                RLP.encodeList(encodedValues.toArray(new byte[encodedValues.size()][]))
        );
    }

    private static void zipFile(ByteArrayInputStream data, ZipOutputStream zipOut) throws IOException {
        ZipEntry zipEntry = new ZipEntry(BOOTSTRAP_DATA_FILE_NAME);
        zipOut.putNextEntry(zipEntry);

        byte[] bytes = new byte[1024];
        for (int length = data.read(bytes); length >= 0; length = data.read(bytes)) {
            zipOut.write(bytes, 0, length);
        }
        data.close();
    }
}
