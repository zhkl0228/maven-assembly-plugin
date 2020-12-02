package org.apache.maven.plugins.assembly.archive.task;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.commons.io.FilenameUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugins.assembly.AssemblerConfigurationSource;
import org.apache.maven.plugins.assembly.archive.ArchiveCreationException;
import org.apache.maven.plugins.assembly.format.AssemblyFormattingException;
import org.apache.maven.plugins.assembly.utils.AssemblyFormatUtils;
import org.apache.maven.plugins.assembly.utils.TypeConversionUtils;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.util.DefaultArchivedFileSet;
import org.codehaus.plexus.archiver.util.DefaultFileSet;
import org.codehaus.plexus.components.io.functions.InputStreamTransformer;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 *
 */
public class AddArtifactTask
{

    public static final String[] DEFAULT_INCLUDES_ARRAY = { "**/*" };

    private final Artifact artifact;

    private final Logger logger;

    private final InputStreamTransformer transformer;

    private final Charset encoding;

    private int directoryMode = -1;

    private int fileMode = -1;

    private boolean unpack = false;

    private List<String> includes;

    private List<String> excludes;

    private boolean usingDefaultExcludes = true;

    private MavenProject project;

    private MavenProject moduleProject;

    private Artifact moduleArtifact;

    private String outputDirectory;

    private String outputFileNameMapping;

    public AddArtifactTask( final Artifact artifact, final Logger logger, InputStreamTransformer transformer,
                            Charset encoding )
    {
        this.artifact = artifact;
        this.logger = logger;
        this.transformer = transformer;
        this.encoding = encoding;
    }

    public AddArtifactTask( final Artifact artifact, final Logger logger, Charset encoding )
    {
        this( artifact, logger, null, encoding );
    }

    public void execute( final Archiver archiver, final AssemblerConfigurationSource configSource )
        throws ArchiveCreationException, AssemblyFormattingException
    {
        if ( artifactIsArchiverDestination( archiver ) )
        {
            artifact.setFile( moveArtifactSomewhereElse( configSource ) );
        }

        String destDirectory =
            AssemblyFormatUtils.getOutputDirectory( outputDirectory, configSource.getFinalName(), configSource,
                                                    AssemblyFormatUtils.moduleProjectInterpolator( moduleProject ),
                                                    AssemblyFormatUtils.artifactProjectInterpolator( project ) );

        boolean fileModeSet = false;
        boolean dirModeSet = false;

        final int oldDirMode = archiver.getOverrideDirectoryMode();
        final int oldFileMode = archiver.getOverrideFileMode();

        if ( fileMode != -1 )
        {
            archiver.setFileMode( fileMode );
            fileModeSet = true;
        }

        if ( directoryMode != -1 )
        {
            archiver.setDirectoryMode( directoryMode );
            dirModeSet = true;
        }
        try
        {

            if ( unpack )
            {
                unpacked( archiver, destDirectory );
            }
            else
            {
                asFile( archiver, configSource, destDirectory );
            }
        }
        finally
        {
            if ( dirModeSet )
            {
                archiver.setDirectoryMode( oldDirMode );
            }

            if ( fileModeSet )
            {
                archiver.setFileMode( oldFileMode );
            }
        }

    }

    private void asFile( Archiver archiver, AssemblerConfigurationSource configSource, String destDirectory )
        throws AssemblyFormattingException, ArchiveCreationException
    {
        final String tempMapping =
            AssemblyFormatUtils.evaluateFileNameMapping( outputFileNameMapping, artifact, configSource.getProject(),
                                                         moduleArtifact, configSource,
                                                         AssemblyFormatUtils.moduleProjectInterpolator( moduleProject ),
                                                         AssemblyFormatUtils.artifactProjectInterpolator( project ) );

        final String outputLocation = destDirectory + tempMapping;

        try
        {
            final File artifactFile = processArtifactFile( artifact.getFile() );

            logger.debug(
                "Adding artifact: " + artifact.getId() + " with file: " + artifactFile + " to assembly location: "
                    + outputLocation + "." );

            if ( fileMode != -1 )
            {
                archiver.addFile( artifactFile, outputLocation, fileMode );
            }
            else
            {
                archiver.addFile( artifactFile, outputLocation );
            }
        }
        catch ( final ArchiverException | IOException e )
        {
            throw new ArchiveCreationException(
                "Error adding file '" + artifact.getId() + "' to archive: " + e.getMessage(), e );
        }
    }

    private File processArtifactFile( File artifactFile ) throws IOException
    {
        if ( !"jar".equalsIgnoreCase( FilenameUtils.getExtension( artifactFile.getName() ) ) )
        {
            return artifactFile;
        }
        if ( !artifactFile.canRead() )
        {
            return artifactFile;
        }

        // Create file descriptors for the jar and a temp jar.

        File tempJarFile = File.createTempFile( FilenameUtils.getBaseName( artifactFile.getName() ), ".jar" );

        // Initialize a flag that will indicate that the jar was updated.

        boolean jarUpdated = false;

        try ( JarFile jar = new JarFile( artifactFile ) )
        {
            // Allocate a buffer for reading entry data.

            byte[] buffer = new byte[1024];
            int bytesRead;

            try ( JarOutputStream tempJar =
                         new JarOutputStream( new FileOutputStream( tempJarFile ) ) )
            {
                // Loop through the jar entries and add them to the temp jar,
                // skipping the entry that was added to the temp jar already.

                for ( Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); )
                {
                    // Get the next entry.

                    JarEntry entry = entries.nextElement();
                    final String ext = FilenameUtils.getExtension( entry.getName() );

                    if ( entry.getName().startsWith( "android/sdk19/" )
                            ||
                            entry.getName().startsWith( "android/sdk23/lib/" ) )
                    {
                        System.out.println( "Skip " + artifactFile.getName() + ": " + entry.getName() );
                        jarUpdated = true;
                        continue;
                    }

                    if ( artifactFile.getName().startsWith( "capstone-" )
                            ||
                            artifactFile.getName().startsWith( "keystone-" ) )
                    {
                        if ( entry.getName().startsWith( "win32-x86/" )
                                ||
                                entry.getName().startsWith( "darwin/" )
                                ||
                                entry.getName().startsWith( "win32-x86-64/" ) )
                        {
                            System.out.println( "Skip " + artifactFile.getName() + ": " + entry.getName() );
                            jarUpdated = true;
                            continue;
                        }
                    }

                    if ( entry.getName().startsWith( "com/sun/jna/win32-x86/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/aix-ppc64/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/darwin/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/linux-x86/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/linux-arm/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/linux-armel/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/linux-aarch64/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/linux-ppc/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/linux-ppc64le/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/linux-mips64el/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/linux-s390x/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/sunos-x86/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/sunos-x86-64/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/sunos-sparc/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/sunos-sparcv9/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/freebsd-x86/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/freebsd-x86-64/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/openbsd-x86/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/openbsd-x86-64/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/win32-x86-64/" )
                            ||
                            entry.getName().startsWith( "com/sun/jna/aix-ppc/" ) )
                    {
                        System.out.println( "Skip " + artifactFile.getName() + ": " + entry.getName() );
                        jarUpdated = true;
                        continue;
                    }

                    if ( entry.getName().startsWith( "natives/osx_64/lib" )
                            ||
                            entry.getName().startsWith( "android/lib/" )
                            ||
                            entry.getName().startsWith( "natives/windows_" ) )
                    {
                        if ( "so".equalsIgnoreCase( ext )
                                ||
                                "dylib".equalsIgnoreCase( ext )
                                ||
                                "dll".equalsIgnoreCase( ext ) )
                        {
                            System.out.println( "Skip " + artifactFile.getName() + ": " + entry.getName() );
                            jarUpdated = true;
                            continue;
                        }
                    }

                    /*if ( entry.getSize() > 64 * 1024 && !"class".equals( ext ) )
                    {
                        System.out.println( "artifactFile=" + artifactFile + ", entry=" + entry );
                    }*/

                    // Get an input stream for the entry.

                    try ( InputStream entryStream = jar.getInputStream( entry ) )
                    {
                        // Read the entry and write it to the temp jar.

                        tempJar.putNextEntry( new JarEntry( entry.getName() ) );

                        while ( ( bytesRead = entryStream.read( buffer ) ) != -1 )
                        {
                            tempJar.write( buffer, 0, bytesRead );
                        }
                    }
                }
            }
        } finally
        {
            // If the jar was not updated, delete the temp jar file.

            if ( jarUpdated )
            {
                tempJarFile.deleteOnExit();
            }
            else
            {
                if ( !tempJarFile.delete() )
                {
                    tempJarFile.deleteOnExit();
                }
            }
        }

        return jarUpdated ? tempJarFile : artifactFile;
    }

    private void unpacked( Archiver archiver, String destDirectory )
        throws ArchiveCreationException
    {
        String outputLocation = destDirectory;

        if ( ( outputLocation.length() > 0 ) && !outputLocation.endsWith( "/" ) )
        {
            outputLocation += "/";
        }

        String[] includesArray = TypeConversionUtils.toStringArray( includes );
        if ( includesArray == null )
        {
            includesArray = DEFAULT_INCLUDES_ARRAY;
        }
        final String[] excludesArray = TypeConversionUtils.toStringArray( excludes );

        try
        {

            final File artifactFile = artifact.getFile();
            if ( artifactFile == null )
            {
                logger.warn(
                    "Skipping artifact: " + artifact.getId() + "; it does not have an associated file or directory." );
            }
            else if ( artifactFile.isDirectory() )
            {
                logger.debug( "Adding artifact directory contents for: " + artifact + " to: " + outputLocation );

                DefaultFileSet fs = DefaultFileSet.fileSet( artifactFile );
                fs.setIncludes( includesArray );
                fs.setExcludes( excludesArray );
                fs.setPrefix( outputLocation );
                fs.setStreamTransformer( transformer );
                fs.setUsingDefaultExcludes( usingDefaultExcludes );
                archiver.addFileSet( fs );
            }
            else
            {
                logger.debug( "Unpacking artifact contents for: " + artifact + " to: " + outputLocation );
                logger.debug( "includes:\n" + StringUtils.join( includesArray, "\n" ) + "\n" );
                logger.debug(
                    "excludes:\n" + ( excludesArray == null ? "none" : StringUtils.join( excludesArray, "\n" ) )
                        + "\n" );
                DefaultArchivedFileSet afs = DefaultArchivedFileSet.archivedFileSet( artifactFile );
                afs.setIncludes( includesArray );
                afs.setExcludes( excludesArray );
                afs.setPrefix( outputLocation );
                afs.setStreamTransformer( transformer );
                afs.setUsingDefaultExcludes( usingDefaultExcludes );
                archiver.addArchivedFileSet( afs, encoding );
            }
        }
        catch ( final ArchiverException e )
        {
            throw new ArchiveCreationException(
                "Error adding file-set for '" + artifact.getId() + "' to archive: " + e.getMessage(), e );
        }
    }

    private File moveArtifactSomewhereElse( AssemblerConfigurationSource configSource )
        throws ArchiveCreationException
    {
        final File tempRoot = configSource.getTemporaryRootDirectory();
        final File tempArtifactFile = new File( tempRoot, artifact.getFile().getName() );

        logger.warn( "Artifact: " + artifact.getId() + " references the same file as the assembly destination file. "
                         + "Moving it to a temporary location for inclusion." );
        try
        {
            FileUtils.copyFile( artifact.getFile(), tempArtifactFile );
        }
        catch ( final IOException e )
        {
            throw new ArchiveCreationException(
                "Error moving artifact file: '" + artifact.getFile() + "' to temporary location: " + tempArtifactFile
                    + ". Reason: " + e.getMessage(), e );
        }
        return tempArtifactFile;
    }

    private boolean artifactIsArchiverDestination( Archiver archiver )
    {
        return ( ( artifact.getFile() != null ) && ( archiver.getDestFile() != null ) ) && artifact.getFile().equals(
            archiver.getDestFile() );
    }

    public void setDirectoryMode( final int directoryMode )
    {
        this.directoryMode = directoryMode;
    }

    public void setFileMode( final int fileMode )
    {
        this.fileMode = fileMode;
    }

    public void setExcludes( final List<String> excludes )
    {
        this.excludes = excludes;
    }

    public void setUsingDefaultExcludes( boolean usingDefaultExcludes )
    {
        this.usingDefaultExcludes = usingDefaultExcludes;
    }

    public void setIncludes( final List<String> includes )
    {
        this.includes = includes;
    }

    public void setUnpack( final boolean unpack )
    {
        this.unpack = unpack;
    }

    public void setProject( final MavenProject project )
    {
        this.project = project;
    }

    public void setOutputDirectory( final String outputDirectory )
    {
        this.outputDirectory = outputDirectory;
    }

    public void setFileNameMapping( final String outputFileNameMapping )
    {
        this.outputFileNameMapping = outputFileNameMapping;
    }

    public void setOutputDirectory( final String outputDirectory, final String defaultOutputDirectory )
    {
        setOutputDirectory( outputDirectory == null ? defaultOutputDirectory : outputDirectory );
    }

    public void setFileNameMapping( final String outputFileNameMapping, final String defaultOutputFileNameMapping )
    {
        setFileNameMapping( outputFileNameMapping == null ? defaultOutputFileNameMapping : outputFileNameMapping );
    }

    public void setModuleProject( final MavenProject moduleProject )
    {
        this.moduleProject = moduleProject;
    }

    public void setModuleArtifact( final Artifact moduleArtifact )
    {
        this.moduleArtifact = moduleArtifact;
    }

}
