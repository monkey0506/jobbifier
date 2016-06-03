/*
 * Copyright (C) 2012 The Android Open Source Project 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at 
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */

package com.android.jobb;

import de.waldheinz.fs.BlockDevice;
import de.waldheinz.fs.FsDirectory;
import de.waldheinz.fs.FsDirectoryEntry;
import de.waldheinz.fs.FsFile;
import de.waldheinz.fs.fat.BootSector;
import de.waldheinz.fs.fat.Fat;
import de.waldheinz.fs.fat.FatFile;
import de.waldheinz.fs.fat.FatFileSystem;
import de.waldheinz.fs.fat.FatLfnDirectory;
import de.waldheinz.fs.fat.FatLfnDirectoryEntry;
import de.waldheinz.fs.fat.FatType;
import de.waldheinz.fs.fat.FatUtils;
import de.waldheinz.fs.fat.SuperFloppyFormatter;
import de.waldheinz.fs.util.FileDisk;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Stack;

public class Main {

    private static final int BLOCK_SIZE = 512; // MUST BE 512
    private static final long MIN_FILE_SIZE = 4301312L;

    public static void printArgs() {
        System.out.println("Jobb -- Create OBB files for use on Android");
        System.out.println();
        System.out.println(" -d <directory> Use <directory> as input/output for OBB files");
        System.out.println(" -k <key>       Use <key> as password to encrypt/decrypt OBB file");
        System.out.println(" -o <filename>  Write OBB file out to <filename>");
        System.out.println(" -v             Verbose mode");
        System.out.println(" -h             Help; this usage screen");
        System.out.println(" -pn <package>  Package name for OBB file");
        System.out.println(" -pv <version>  Package version for OBB file");
        System.out.println(" -ov            Set overlay flag");
        System.out.println(" -dump <file>   Parse and dump OBB file");        
        System.out.println(" -about         Notices about this tool");
        System.out.println();
        System.out.println("Example: Dump the contents of the encrypted OBB file to the directory");
        System.out.println("         jobb -dump myfile.obb -d ./mydirectory -k mypassword");
        System.out.println();
        System.out.println("Example: Create an encrypted OBB file by recursing the directory path");
        System.out.println("         jobb -pn package.name -pv 1 -d ./mydirectory -k mypassword");
    }

    static String sDirectory;
    static File sDirectoryFile;
    static boolean sHasOutputDirectory;
    static String sKey;
    static String sOutputFile;
    static boolean sVerboseMode;
    static String sPackageName;
    static int sPackageVersion = -1;
    static byte[] sSalt;
    static boolean sOverlay;
    static int sFlags;
    static String sInputFile;
    static byte[] sFishKey;

    private interface FileProcessor {
        void processFile(File f);

        void processDirectory(File f);

                /**
                 * @param dir
                 */
                void endDirectory(File dir);
    }

    static ByteBuffer sTempBuf = ByteBuffer.allocate(1024*1024);
    
    static public void dumpDirectory(FsDirectory dir, int tabStop, File curDirectory) throws IOException {
        Iterator<FsDirectoryEntry> i = dir.iterator();
        while (i.hasNext()) {
            final FsDirectoryEntry e = i.next();
            if (e.isDirectory()) {
                for (int idx = 0; idx < tabStop; idx++)
                    System.out.print(' ');
                if (e.getName().equals(".") || e.getName().equals(".."))
                    continue;
                for (int idx = 0; idx < tabStop; idx++)
                    System.out.print("  ");
                System.out.println("[" + e + "]");
                dumpDirectory(e.getDirectory(), tabStop + 1, new File(curDirectory, e.getName()));
            } else {
                for ( int idx = 0; idx < tabStop; idx++ ) System.out.print("  ");
                System.out.println( e );
                if ( sHasOutputDirectory ) {
                    if ( !curDirectory.exists() ) {
                        if ( false == curDirectory.mkdirs() ) {
                            throw new IOException("Unable to create directory: " + curDirectory);                            
                        }
                    }
                    File curFile = new File(curDirectory, e.getName());
                    if ( curFile.exists() ) {
                        throw new IOException("File exists: " + curFile); 
                    } else {
                        FsFile f = e.getFile();
                        FileOutputStream fos = null;
                        try {
                            fos = new FileOutputStream(curFile);
                            FileChannel outputChannel = fos.getChannel();
                            int capacity = sTempBuf.capacity();
                            long length = f.getLength();
                            for ( long pos = 0; pos < length; ) {
                                int readLength = (int)(length-pos > capacity ? capacity : length-pos);
                                sTempBuf.rewind();
                                sTempBuf.limit(readLength);
                                f.read(pos, sTempBuf);
                                sTempBuf.rewind();
                                while(sTempBuf.remaining() > 0)
                                    outputChannel.write(sTempBuf);
                                pos += readLength;
                            }
                        } finally {
                            if ( null != fos ) fos.close();
                        }
                    }
                }
            }
        }
    }

    /**
     * @param args
     */
    public static void main(String[] args) {
        boolean displayHelp = false;
        boolean isEncrypted = false;
        try {
            for (int i = 0; i < args.length; i++) {
                String curArg = args[i];
                if (curArg.equals("-d")) {
                    sDirectory = args[++i];
                    sDirectoryFile = new File(sDirectory);
                    sHasOutputDirectory = true;
                } else if (curArg.equals("-k")) {
                    isEncrypted = true;
                    sKey = args[++i];
                } else if (curArg.equals("-o")) {
                    sOutputFile = args[++i];
                } else if (curArg.equals("-h")) {
                    displayHelp = true;
                } else if (curArg.equals("-v")) {
                    sVerboseMode = true;
                } else if (curArg.equals("-pn")) {
                    sPackageName = args[++i];
                } else if (curArg.equals("-pv")) {
                    sPackageVersion = Integer.parseInt(args[++i]);
                } else if (curArg.equals("-ov")) {
                    sFlags |= ObbFile.OBB_OVERLAY;
                } else if (curArg.equals("-dump")) {
                    sInputFile = args[++i];
                } else if (curArg.equals("-salt")) {
                    // special case --- use same salt
                    String saltString = args[++i];
                    BigInteger bi = new BigInteger(saltString, 16);
                    sSalt = bi.toByteArray();
                    if ( sSalt.length != PBKDF.SALT_LEN ) {
                        displayHelp = true;
                    }
                } else if (curArg.equals("-about")) {
                    System.out.println("-------------------------------------------------------------------------------");
                    System.out.println("Portions of this code:");             
                    System.out.println("-------------------------------------------------------------------------------");
                    System.out.println("Copyright (c) 2000 The Legion Of The Bouncy Castle");
                    System.out.println("(http://www.bouncycastle.org)");
                    System.out.println();
                    System.out.println("Permission is hereby granted, free of charge, to any person obtaining");
                    System.out.println("a copy of this software and associated documentation files (the \"Software\"");
                    System.out.println("to deal in the Software without restriction, including without limitation");
                    System.out.println("the rights to use, copy, modify, merge, publish, distribute, sublicense");
                    System.out.println("and/or sell copies of the Software, and to permit persons to whom the Software");
                    System.out.println("is furnished to do so, subject to the following conditions:");
                    System.out.println();
                    System.out.println("The above copyright notice and this permission notice shall be included in all");
                    System.out.println("copies or substantial portions of the Software.");
                    System.out.println("-------------------------------------------------------------------------------");
                    System.out.println("Twofish is uncopyrighted and license-free, and was created and analyzed by:");                   
                    System.out.println("Bruce Schneier - John Kelsey - Doug Whiting");
                    System.out.println("David Wagner - Chris Hall - Niels Ferguson");
                    System.out.println("-------------------------------------------------------------------------------");
                    System.out.println("Cryptix General License");
                    System.out.println();
                    System.out.println("Copyright (c) 1995-2005 The Cryptix Foundation Limited.");
                    System.out.println("All rights reserved.");
                    System.out.println("");
                    System.out.println("Redistribution and use in source and binary forms, with or without");
                    System.out.println("modification, are permitted provided that the following conditions are");
                    System.out.println("met:");
                    System.out.println();
                    System.out.println(" 1. Redistributions of source code must retain the copyright notice,");
                    System.out.println("    this list of conditions and the following disclaimer.");
                    System.out.println(" 2. Redistributions in binary form must reproduce the above copyright");
                    System.out.println("    notice, this list of conditions and the following disclaimer in");
                    System.out.println("    the documentation and/or other materials provided with the");
                    System.out.println("    distribution.");
                    System.out.println("-------------------------------------------------------------------------------");
                    return;
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            displayHelp = true;
        }
        if (null != sInputFile) {
            ObbFile obbFile = new ObbFile();
            obbFile.readFrom(sInputFile);
            System.out.print("Package Name: ");
            System.out.println(obbFile.mPackageName);
            System.out.print("Package Version: ");
            System.out.println(obbFile.mPackageVersion);            
            if (0 != (obbFile.mFlags & ObbFile.OBB_SALTED)) {
                System.out.print("SALT: ");
                BigInteger bi = new BigInteger(obbFile.mSalt);
                System.out.println(bi.toString(16));
                System.out.println();
                if ( null == sKey ) {
                    System.out.println("Encrypted file. Please add password.");
                    return;
                }
                try {
                    sFishKey = PBKDF.getKey(sKey,obbFile.mSalt);
                    bi = new BigInteger(sFishKey);
                    System.out.println(bi.toString(16));                    
                } catch (InvalidKeyException e) {
                    e.printStackTrace();
                } catch (NoSuchAlgorithmException e) {
                    e.printStackTrace();
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                isEncrypted = true;
            } else {
                isEncrypted = false;
            }
            File obbInputFile = new File(sInputFile);

            BlockDevice fd;
            try {
                if ( isEncrypted ) {
                    EncryptedBlockFile ebf = new EncryptedBlockFile(sFishKey, obbInputFile, "r");
                    fd = new FileDisk(ebf, ebf.getEncryptedFileChannel(), true);
                } else {
                    fd = new FileDisk(obbInputFile, true);
                }
                final FatFileSystem fatFs = FatFileSystem.read(fd, true);
                final BootSector bs = fatFs.getBootSector();
                final FsDirectory rootDir = fatFs.getRoot();
                if (sVerboseMode) {
                    System.out.print("Filesystem Type: ");
                    FatType ft = bs.getFatType();
                    if (ft == FatType.FAT32) {
                        System.out.println("FAT32");
                    } else if (ft == FatType.FAT16) {
                        System.out.println("FAT16");
                    } else if (ft == FatType.FAT12) {
                        System.out.println("FAT12");
                    } else {
                        System.out.println("Unknown");
                    }
                    System.out.print("           OEM Name: ");
                    System.out.println(bs.getOemName());
                    System.out.print("   Bytes Per Sector: ");
                    System.out.println(bs.getBytesPerSector());
                    System.out.print("Sectors per cluster: ");
                    System.out.println(bs.getSectorsPerCluster());
                    System.out.print("   Reserved Sectors: ");
                    System.out.println(bs.getNrReservedSectors());
                    System.out.print("               Fats: ");
                    System.out.println(bs.getNrFats());
                    System.out.print("   Root Dir Entries: ");
                    System.out.println(bs.getRootDirEntryCount());
                    System.out.print("  Medium Descriptor: ");
                    System.out.println(bs.getMediumDescriptor());
                    System.out.print("            Sectors: ");
                    System.out.println(bs.getSectorCount());
                    System.out.print("    Sectors Per Fat: ");
                    System.out.println(bs.getSectorsPerFat());
                    System.out.print("              Heads: ");
                    System.out.println(bs.getNrHeads());
                    System.out.print("     Hidden Sectors: ");
                    System.out.println(bs.getNrHiddenSectors());
                    System.out.print("         Fat Offset: ");
                    System.out.println(FatUtils.getFatOffset(bs, 0));
                    System.out.println("          RootDir: " + rootDir);
                }
                dumpDirectory(rootDir, 0, sDirectoryFile);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InvalidKeyException e) {
                e.printStackTrace();
            }
            return;
        }
        boolean printArgs;
        if (displayHelp) {
                printArgs = true;
        } else if ( null == sDirectory ) {
                printArgs = true;
                System.out.println("A directory to be recursed through [-d directory] is required when creating an OBB filesystem.");
        } else if ( null == sOutputFile ) {
                printArgs = true;
                System.out.println("An output filename [-o outputfile] is required when creating an OBB filesystem.");          
        } else if ( null == sPackageName ) {
                printArgs = true;
                System.out.println("A package name [-pn package] is required when creating an OBB filesystem.");                                
        } else if ( -1 == sPackageVersion ) {
                printArgs = true;               
                System.out.println("A package version [-pv package] is required when creating an OBB filesystem.");                             
        } else {
                printArgs = false;
        }
        if (printArgs) {
            printArgs();
        } else {
            if (null != sKey) {
                if ( null == sSalt ) {
                    sSalt = PBKDF.getRandomSalt();
                }
                try {
                    sFishKey = PBKDF.getKey(sKey, sSalt);
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }

                boolean bFindRightKey = false;

                do {
                    if (null == sSalt) {
                        sSalt = PBKDF.getRandomSalt();
                    }
                    try {
                        sFishKey = PBKDF.getKey(sKey, sSalt);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return;
                    }

                    BigInteger bi = new BigInteger(sFishKey);
                    String hashedKey = bi.toString(16);
                    String  pbkdfKeyString = PBKDF.bytesToHexString(sFishKey);
                    if (!hashedKey.equals(pbkdfKeyString)){
                        sSalt = PBKDF.getRandomSalt();
                    } else {
                        bFindRightKey = true;
                    }
                } while (!bFindRightKey);

                sFlags |= ObbFile.OBB_SALTED;
                if (sVerboseMode) {
                    System.out.println("Crypto: ");
                    System.out.print("SALT: ");
                    for (byte b : sSalt) {
                        int out = b & 0xFF;
                        System.out.print(Integer.toHexString(out));
                        System.out.print(" ");
                    }
                    System.out.print("\t");
                    System.out.print("KEY: ");
                    for (byte b : sFishKey) {
                        int out = b & 0xFF;
                        System.out.print(Integer.toHexString(out));
                        System.out.print(" ");
                    }
                    System.out.println();
                }
            }
            if (sVerboseMode) {
                System.out.println("Scanning directory: " + sDirectory);
            }
            final File f = new File(sDirectory);
            
            long fileSize = getTotalFileSize(f, 0);
            fileSize = Math.max(MIN_FILE_SIZE, getTotalFileSize(f, BLOCK_SIZE *
            		SuperFloppyFormatter.clusterSizeFromSize(Math.max(MIN_FILE_SIZE, fileSize), BLOCK_SIZE, FatType.FAT16)));
            if (fileSize >= 2147483648L) throw new UnsupportedOperationException("File size limit exceeded: " + fileSize);
            if (sVerboseMode) {
                System.out.println("Total Files: " + fileSize);
            }
            long numSectors = fileSize / BLOCK_SIZE;
            long clusterSize = SuperFloppyFormatter.clusterSizeFromSize(fileSize, BLOCK_SIZE, FatType.FAT16);
            long fatOverhead = 2*numSectors/clusterSize*4;
            fatOverhead += clusterSize*BLOCK_SIZE - fatOverhead % (clusterSize*BLOCK_SIZE);
            fatOverhead += 2*clusterSize; //start at second cluster
            if (sVerboseMode) {
                System.out.println("FAT Overhead: " + fatOverhead);
            }
            long clusterSizeInBytes = clusterSize * BLOCK_SIZE;
            long filesystemSize = (( numSectors ) * BLOCK_SIZE + fatOverhead + clusterSizeInBytes -1 ) / clusterSizeInBytes * clusterSizeInBytes;            
            if (sVerboseMode) {
                System.out.println("Filesystem Size: " + filesystemSize);
            }
            File fsFile = new File(sOutputFile);
            if (fsFile.exists())
                fsFile.delete();
            try {
                BlockDevice fd;
                if ( isEncrypted ) {
                    try {
                        EncryptedBlockFile ebf = new EncryptedBlockFile(sFishKey, fsFile, "rw");
                        ebf.setLength(filesystemSize);
                        fd = new FileDisk(ebf, ebf.getEncryptedFileChannel(), false);
                    } catch (InvalidKeyException e) {
                        e.printStackTrace();
                        return;
                    }
                } else {
                    fd = FileDisk.create(fsFile, filesystemSize);
                }
                // FAT type is ONLY FAT16
                final FatFileSystem fs = SuperFloppyFormatter.get(fd, FatType.FAT16).format();
                final String rootPath = f.getAbsolutePath();
                // add the files into the filesystem
                processAllFiles(f, new FileProcessor() {
                    Stack<FatLfnDirectory> mCurDir = new Stack<FatLfnDirectory>();

                    @Override
                    public void processDirectory(File curFile) {
                        String directory = curFile.getAbsolutePath().substring(rootPath.length());
                        if (sVerboseMode) {
                            System.out.println("Processing Directory: " + directory + " at cluster " + fs.getFat().getLastFreeCluster());
                        }
                        FatLfnDirectory curDir = fs.getRoot();
                        if (directory.length() > 0) {
                            File tempFile = new File(directory);
                            Stack<String> pathStack = new Stack<String>();
                            do {
                                pathStack.push(tempFile.getName());
                            } while (null != (tempFile = tempFile.getParentFile()));
                            while (!pathStack.empty()) {
                                String name = pathStack.pop();
                                if (0 == name.length())
                                    continue;
                                FatLfnDirectoryEntry entry = curDir.getEntry(name);
                                if (null != entry) {
                                    if (!entry.isDirectory()) {
                                        throw new RuntimeException(
                                                "File path not FAT compatible - naming conflict!");
                                    }
                                } else {
                                    try {
                                        if (sVerboseMode) {
                                            System.out.println("Adding Directory: " + name);
                                        }
                                        entry = curDir.addDirectory(name);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                        throw new RuntimeException("Error adding directory!");
                                    }
                                }
                                try {
                                    curDir = entry.getDirectory();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    throw new RuntimeException("Error getting directory");
                                }
                            }
                        }
                        mCurDir.push(curDir);
                    }

                    @SuppressWarnings("resource")
					@Override
                    public void processFile(File curFile) {
                        FatLfnDirectoryEntry entry;
                        FatLfnDirectory curDir = mCurDir.peek();
                        try {
                            if (sVerboseMode) {
                                System.out.println("Adding file: "
                                        + curFile.getAbsolutePath().substring(rootPath.length())
                                        + " with length " + curFile.length() + " at cluster " + fs.getFat().getLastFreeCluster());
                            }
                            entry = curDir.addFile(curFile.getName());
                        } catch (IOException e) {
                            e.printStackTrace();
                            throw new RuntimeException("Error adding file with name: "
                                    + curFile.getName());
                        }
                        ReadableByteChannel channel = null;
                        try {
                            FatFile f = entry.getFile();
                            channel = new FileInputStream(curFile).getChannel();
                            ByteBuffer buf = ByteBuffer.allocateDirect(1024 * 512);
                            int numRead = 0;
                            long offset = 0;
                            while (true) {
                                buf.clear();
                                numRead = channel.read(buf);
                                if (numRead < 0)
                                    break;
                                buf.rewind();
                                buf.limit(numRead);
                                f.write(offset, buf);
                                offset += numRead;
                            }
                            f.flush();
                        } catch (IOException e) {
                            e.printStackTrace();
                            throw new RuntimeException("Error getting/writing file with name: "
                                    + curFile.getName());
                        } finally {
                            if (null != channel)
                                try {
                                    channel.close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                        }
                    }

                                        @Override
                                        public void endDirectory(File dir) {
                                                mCurDir.pop();
                                        }
                                        
                });
                fs.flush();
                fs.close();
                @SuppressWarnings("unused")
				Fat fat = fs.getFat();
                ObbFile ob = new ObbFile();
                ob.setPackageName(sPackageName);
                ob.setPackageVersion(sPackageVersion);
                ob.setFlags(sFlags);
                if (null != sSalt) {
                    ob.setSalt(sSalt);
                }
                ob.writeTo(fsFile);                
                if (sVerboseMode) {
                    System.out.println("Success!");
                    System.out.println("" + fs.getTotalSpace() + " bytes total");
                    System.out.println("" + fs.getFreeSpace() + " bytes free");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            catch (UnsupportedOperationException e)
            {
            	e.printStackTrace();
            }
        }
    }

    public static long getTotalFileSize(File dir, final int clusterSize) {
        final long[] mSize = new long[3];
        final boolean calculateSlop = clusterSize > 0;
        processAllFiles(dir, new FileProcessor() {
            Stack<int[]> mDirLen = new Stack<int[]>();
            
            @Override
            public void processFile(File f) {
                if (sVerboseMode) {
                    System.out.println("Adding size for file: " + f.getAbsolutePath());
                }
                long length = f.length();
                if ( calculateSlop && length > 0 ) {
                    int[] dirLen = mDirLen.peek();
                        long realLength = ((clusterSize-1)+length) / clusterSize*clusterSize;
                    long slop = realLength-length;
                    length += slop;
                        mSize[0] += length;
                    mSize[1] += slop;
                    dirLen[0] += f.getName().length()/13+3;
                } else {
                        mSize[0] += length;
                }
            }

            @Override
            public void processDirectory(File f) {
                if ( calculateSlop ) {
                        int[] dirLen = new int[1];
                    dirLen[0] += f.getName().length()/13+4;
                        mDirLen.push(dirLen);
                }
            }

                        @Override
                        public void endDirectory(File dir) {
                if ( calculateSlop ) {
                        int[] dirLen = mDirLen.pop();
                        long lastDirLen = dirLen[0] * 32;
                        if ( lastDirLen != 0 ) {
                        long realLength = ((clusterSize-1)+lastDirLen) / clusterSize*clusterSize;
                                long slop = realLength-lastDirLen;
                                mSize[0] += lastDirLen + slop;
                                mSize[1] += slop;
                                mSize[2] += lastDirLen;
                        }
                }                               
                        }
        });
        System.out.println("Slop: " + mSize[1] + "   Directory Overhead: " + mSize[2] );
        return mSize[0];
    }

    // Process all files and directories under dir
    public static void processAllFiles(File dir, FileProcessor fp) {
        if (dir.isDirectory()) {
            fp.processDirectory(dir);
            String[] children = dir.list();
            for (int i = 0; i < children.length; i++) {
                processAllFiles(new File(dir, children[i]), fp);
            }
            fp.endDirectory(dir);
        } else {
            fp.processFile(dir);
        }
    }
}