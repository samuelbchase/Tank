/**
 * Copyright 2011 Intuit Inc. All Rights Reserved
 */
package com.intuit.tank.service.impl.v1.datafile;

/*
 * #%L
 * Datafile Rest Service
 * %%
 * Copyright (C) 2011 - 2015 Intuit Inc.
 * %%
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * #L%
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.StreamingOutput;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Source;
import javax.xml.transform.sax.SAXSource;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.intuit.tank.api.model.v1.datafile.DataFileDescriptor;
import com.intuit.tank.api.model.v1.datafile.DataFileDescriptorContainer;
import com.intuit.tank.dao.DataFileDao;
import com.intuit.tank.datafile.util.DataFileServiceUtil;
import com.intuit.tank.project.DataFile;
import com.intuit.tank.service.api.v1.DataFileService;
import com.intuit.tank.service.util.ResponseUtil;
import com.intuit.tank.storage.FileData;
import com.intuit.tank.storage.FileStorage;
import com.intuit.tank.storage.FileStorageFactory;
import com.intuit.tank.util.DataFileUtil;
import com.intuit.tank.vm.settings.TankConfig;

/**
 * DataFileServiceV1
 * 
 * @author dangleton
 * 
 */
@Path(DataFileService.SERVICE_RELATIVE_PATH)
public class DataFileServiceV1 implements DataFileService {

    private static final Logger LOG = LogManager.getLogger(DataFileServiceV1.class);

    /**
     * @inheritDoc
     */
    @Override
    public String ping() {
        return "PONG " + getClass().getSimpleName();
    }

    /**
     * @inheritDoc
     */
    @Override
    public StreamingOutput getDataFileData(Integer id) {
        return getDataFileVersion(id, null, 0, -1);
    }

    /**
     * @inheritDoc
     */
    @Override
    public StreamingOutput getDataFileDataOffset(Integer id, int offset, int numLines) {
        return getDataFileVersion(id, null, offset, numLines);
    }

    /**
     * @inheritDoc
     */
    @Override
    public StreamingOutput getDataFileVersion(Integer id, Integer version, final int offset, final int numLines) {
        DataFileDao dataFileDao = new DataFileDao();
        DataFile dataFile = dataFileDao.findById(id);
        return getStreamingOutput(offset, numLines, dataFile);
    }

    /**
     * @inheritDoc
     */
    @Override
    public Response saveOrUpdateDataFile(FormDataMultiPart formData) {
        DataFile dataFile = null;
        InputStream is = null;
        Map<String, List<FormDataBodyPart>> fields = formData.getFields();
        DataFileDao dao = new DataFileDao();
        for (Entry<String, List<FormDataBodyPart>> entry : fields.entrySet()) {
            String formName = entry.getKey();
            LOG.debug("Entry name: " + formName);
            for (FormDataBodyPart part : entry.getValue()) {
                MediaType mediaType = part.getMediaType();
                if (MediaType.APPLICATION_XML_TYPE.equals(mediaType)
                        || MediaType.APPLICATION_JSON_TYPE.equals(mediaType)) {
                    DataFileDescriptor dfd = part.getEntityAs(DataFileDescriptor.class);
                    dataFile = descriptorToDataFile(dao, dfd);
                } else if (MediaType.TEXT_PLAIN_TYPE.equals(mediaType)) {
                    String s = part.getEntityAs(String.class);
                    LOG.debug("Plain Text Value: " + s);
                    if ("xmlString".equalsIgnoreCase(formName)) {
                        try {
                        	//Source: https://www.owasp.org/index.php/XML_External_Entity_(XXE)_Prevention_Cheat_Sheet#Unmarshaller
                        	SAXParserFactory spf = SAXParserFactory.newInstance();
                        	spf.setNamespaceAware(true);
                        	spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                        	spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                        	spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                        	
                        	Source xmlSource = new SAXSource(spf.newSAXParser().getXMLReader(), new InputSource(new StringReader(s)));
                            JAXBContext ctx = JAXBContext.newInstance(DataFileDescriptor.class.getPackage().getName());
                            DataFileDescriptor dfd = (DataFileDescriptor) ctx.createUnmarshaller().unmarshal(xmlSource);
                            dataFile = descriptorToDataFile(dao, dfd);
                        } catch (JAXBException e) {
                            throw new RuntimeException(e);
                        } catch (SAXException saxe) {
                            throw new RuntimeException(saxe);
                        } catch (ParserConfigurationException pce) {
                            throw new RuntimeException(pce);
                        }
                    }
                } else if (MediaType.APPLICATION_OCTET_STREAM_TYPE.equals(mediaType)) {
                    // get the file
                    is = part.getValueAs(InputStream.class);
                }
            }
        }
        ResponseBuilder responseBuilder = Response.ok();
        if (dataFile == null) {
            responseBuilder = Response.status(Status.BAD_REQUEST);
            responseBuilder.entity("Requests to store Datafiles must include a DataFileDescriptor.");
        } else {
            try {
                dataFile = dao.storeDataFile(dataFile, is);
            } finally {
                IOUtils.closeQuietly(is);
            }
            DataFileDescriptor result = DataFileServiceUtil.dataFileToDescriptor(dataFile);
            responseBuilder.entity(result);
        }
        // cache control
        // CacheControl cc = new CacheControl();
        // cc.setMaxAge(60 * 10);// ten minutes
        // cc.setMustRevalidate(false);
        // responseBuilder.cacheControl(cc);
        // eTag logic
        // EntityTag eTag = new EntityTag(offeringId, true);
        // responseBuilder.tag(eTag);
        responseBuilder.cacheControl(ResponseUtil.getNoStoreCacheControl());
        return responseBuilder.build();

    }

    private DataFile descriptorToDataFile(DataFileDao dao,
            DataFileDescriptor dfd) {
        DataFile dataFile;
        if (dfd.getId() != null && dfd.getId() != 0) {
            dataFile = dao.findById(dfd.getId());
            dataFile.setComments(dfd.getComments());
            dataFile.setPath(dfd.getName());
            dataFile.setCreator(dfd.getCreator());
        } else {
            dataFile = DataFileServiceUtil.descriptorToDataFile(dfd);
        }
        return dataFile;
    }

    /**
     * @inheritDoc
     */
    @Override
    public Response deleteDataFile(Integer id) {
        ResponseBuilder responseBuilder = Response.ok();
        DataFileDao dao = new DataFileDao();
        dao.delete(id);
        responseBuilder.cacheControl(ResponseUtil.getNoStoreCacheControl());
        return responseBuilder.build();
    }

    /**
     * @inheritDoc
     */
    @Override
    public Response getDataFiles() {
        ResponseBuilder responseBuilder = Response.ok();
        DataFileDao dao = new DataFileDao();
        List<DataFile> all = dao.findAll();
        List<DataFileDescriptor> result = all.stream().map(DataFileServiceUtil::dataFileToDescriptor).collect(Collectors.toList());

        responseBuilder.entity(new DataFileDescriptorContainer(result));
        responseBuilder.cacheControl(ResponseUtil.getNoStoreCacheControl());
        return responseBuilder.build();
    }

    /**
     * @inheritDoc
     */
    @Override
    public Response getDataFile(Integer id) {
        ResponseBuilder responseBuilder = Response.ok();
        DataFileDao dao = new DataFileDao();
        DataFile df = dao.findById(id);
        if (df != null) {
            responseBuilder.entity(DataFileServiceUtil.dataFileToDescriptor(df));
        }
        responseBuilder.cacheControl(ResponseUtil.getNoStoreCacheControl());
        return responseBuilder.build();
    }

    /**
     * @inheritDoc
     */
    @Override
    public Response setDataFile(Integer id, FormDataMultiPart formData) {
        return saveOrUpdateDataFile(formData);
    }

    /**
     * @param offset
     * @param numLines
     * @param dataFile
     * @return
     */
    private StreamingOutput getStreamingOutput(final int offset, final int numLines, DataFile dataFile) {
        final FileStorage fileStorage = FileStorageFactory.getFileStorage(new TankConfig().getDataFileStorageDir(), false);
        final FileData fd = DataFileUtil.getFileData(dataFile);
        return outputStream -> {

            try (   BufferedReader in = new BufferedReader(new InputStreamReader(fileStorage.readFileData(fd), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(outputStream) ) {
                int nl = numLines;
                if (!fd.getFileName().toLowerCase().endsWith(".csv")) {
                    nl = -1;
                }
                // Read File Line By Line
                String strLine;
                int lineNum = 0;
                int os = offset < 0 ? 0 : offset;
                while ((strLine = in.readLine()) != null && (nl < 0 || lineNum < (os + numLines))) {
                    if (numLines < 0 || lineNum >= os) {
                        out.println(strLine);
                    }
                    lineNum++;
                }
            } catch (IOException e) {
                LOG.error("Error streaming file: " + e.toString(), e);
                throw new WebApplicationException(e, Response.Status.INTERNAL_SERVER_ERROR);
            }
        };
    }

    /**
     * @inheritDoc
     */
    @Override
    public Response downloadDataFileData(Integer id) {
        ResponseBuilder responseBuilder = Response.ok();
        DataFileDao dataFileDao = new DataFileDao();
        DataFile dataFile = dataFileDao.findById(id);
        StreamingOutput streamingOutput = getStreamingOutput(0, -1, dataFile);
        String filename = dataFile.getPath();
        responseBuilder.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        responseBuilder.entity(streamingOutput);
        return responseBuilder.build();
    }

}
