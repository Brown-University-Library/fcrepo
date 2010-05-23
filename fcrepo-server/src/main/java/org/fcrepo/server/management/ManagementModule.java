/* The contents of this file are subject to the license and copyright terms
 * detailed in the license directory at the root of the source tree (also
 * available online at http://fedora-commons.org/license/).
 */
package org.fcrepo.server.management;

import org.fcrepo.server.Context;
import org.fcrepo.server.Module;
import org.fcrepo.server.Server;
import org.fcrepo.server.errors.ModuleInitializationException;
import org.fcrepo.server.errors.ModuleShutdownException;
import org.fcrepo.server.errors.ServerException;
import org.fcrepo.server.proxy.AbstractInvocationHandler;
import org.fcrepo.server.proxy.ProxyFactory;
import org.fcrepo.server.security.Authorization;
import org.fcrepo.server.storage.DOManager;
import org.fcrepo.server.storage.ExternalContentManager;
import org.fcrepo.server.storage.types.Datastream;
import org.fcrepo.server.storage.types.RelationshipTuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.util.*;

/**
 * @author Edwin Shin
 * @version $Id$
 */
public class ManagementModule
        extends Module
        implements Management, ManagementDelegate {

    private static final Logger logger =
            LoggerFactory.getLogger(ManagementModule.class);

    private Authorization m_fedoraXACMLModule;

    private DOManager m_manager;

    private ExternalContentManager m_contentManager;

    private int m_uploadStorageMinutes;

    private int m_lastId;

    private File m_tempDir;

    private Hashtable<String, Long> m_uploadStartTime;

    private Management mgmt;

    private AbstractInvocationHandler[] invocationHandlers;

    /**
     * Delay between purge of two uploaded files.
     */
    private long m_purgeDelayInMillis;

    public ManagementModule(Map<String, String> moduleParameters,
                            Server server,
                            String role)
            throws ModuleInitializationException {
        super(moduleParameters, server, role);
    }

    @Override
    public void initModule() throws ModuleInitializationException {

        // how many minutes should we hold on to uploaded files? default=5
        String min = getParameter("uploadStorageMinutes");
        if (min == null) {
            min = "5";
        }
        try {
            m_uploadStorageMinutes = Integer.parseInt(min);
            if (m_uploadStorageMinutes < 1) {
                throw new ModuleInitializationException("uploadStorageMinutes "
                                                        + "must be 1 or more, if specified.", getRole());
            }
        } catch (NumberFormatException nfe) {
            throw new ModuleInitializationException("uploadStorageMinutes must "
                                                    + "be an integer, if specified.",
                                                    getRole());
        }
        // initialize storage area by 1) ensuring the directory is there
        // and 2) reading in the existing files, if any, and setting their
        // startTime to the current time.
        try {
            m_tempDir = new File(getServer().getHomeDir(), "management/upload");
            if (!m_tempDir.isDirectory()) {
                m_tempDir.mkdirs();
            }
            // put leftovers in hash, while saving highest id as m_lastId
            m_uploadStartTime = new Hashtable<String, Long>();
            String[] fNames = m_tempDir.list();
            Long leftoverStartTime = new Long(System.currentTimeMillis());
            m_lastId = 0;
            for (String element : fNames) {
                try {
                    int id = Integer.parseInt(element);
                    if (id > m_lastId) {
                        m_lastId = id;
                    }
                    m_uploadStartTime.put(element, leftoverStartTime);
                } catch (NumberFormatException nfe) {
                    // skip files that aren't named numerically
                }
            }
        } catch (Exception e) {
            throw new ModuleInitializationException("Error while initializing "
                                                    + "temporary storage area: " + e.getClass().getName()
                                                    + ": " + e.getMessage(), getRole(), e);
        }

        // initialize variables pertaining to checksumming datastreams.
        String auto = getParameter("autoChecksum");
        logger.debug("Got Parameter: autoChecksum = " + auto);
        if (auto.equalsIgnoreCase("true")) {
            Datastream.autoChecksum = true;
            Datastream.defaultChecksumType = getParameter("checksumAlgorithm");
        }
        logger.debug("autoChecksum is " + auto);
        logger.debug("defaultChecksumType is " + Datastream.defaultChecksumType);

        // get delay between purge of two uploaded files (default 1 minute)
        String purgeDelayInMillis = getParameter("purgeDelayInMillis");
        if (purgeDelayInMillis == null) {
            purgeDelayInMillis = "60000";
        }
        try {
            this.m_purgeDelayInMillis = Integer.parseInt(purgeDelayInMillis);
        } catch (NumberFormatException nfe) {
            throw new ModuleInitializationException(
                    "purgeDelayInMillis must be an integer, if specified.",
                    getRole());
        }
    }

    @Override
    public void postInitModule() throws ModuleInitializationException {
        // Verify required modules have been loaded
        m_manager =
                (DOManager) getServer()
                        .getModule("org.fcrepo.server.storage.DOManager");
        if (m_manager == null) {
            throw new ModuleInitializationException("Can't get a DOManager "
                                                    + "from Server.getModule", getRole());
        }
        m_contentManager =
                (ExternalContentManager) getServer()
                        .getModule("org.fcrepo.server.storage.ExternalContentManager");
        if (m_contentManager == null) {
            throw new ModuleInitializationException("Can't get an ExternalContentManager "
                                                    + "from Server.getModule",
                                                    getRole());
        }

        m_fedoraXACMLModule =
                (Authorization) getServer()
                        .getModule("org.fcrepo.server.security.Authorization");
        if (m_fedoraXACMLModule == null) {
            throw new ModuleInitializationException(
                    "Can't get Authorization module (in default management) from Server.getModule",
                    getRole());
        }

        Management m =
                new DefaultManagement(m_fedoraXACMLModule,
                                      m_manager,
                                      m_contentManager,
                                      m_uploadStorageMinutes,
                                      m_lastId,
                                      m_tempDir,
                                      m_uploadStartTime,
                                      m_purgeDelayInMillis);

        mgmt = getProxyChain(m);
    }

    @Override
    public void shutdownModule() throws ModuleShutdownException {
        if (invocationHandlers != null) {
            for (AbstractInvocationHandler h : invocationHandlers) {
                h.close();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public String addDatastream(Context context,
                                String pid,
                                String dsID,
                                String[] altIDs,
                                String dsLabel,
                                boolean versionable,
                                String MIMEType,
                                String formatURI,
                                String location,
                                String controlGroup,
                                String dsState,
                                String checksumType,
                                String checksum,
                                String logMessage) throws ServerException {
        return mgmt.addDatastream(context,
                                  pid,
                                  dsID,
                                  altIDs,
                                  dsLabel,
                                  versionable,
                                  MIMEType,
                                  formatURI,
                                  location,
                                  controlGroup,
                                  dsState,
                                  checksumType,
                                  checksum,
                                  logMessage);
    }

    /**
     * {@inheritDoc}
     */
    public boolean addRelationship(Context context,
                                   String pid,
                                   String relationship,
                                   String object,
                                   boolean isLiteral,
                                   String datatype) throws ServerException {
        return mgmt.addRelationship(context,
                                    pid,
                                    relationship,
                                    object,
                                    isLiteral,
                                    datatype);
    }

    /**
     * {@inheritDoc}
     */
    public String compareDatastreamChecksum(Context context,
                                            String pid,
                                            String dsID,
                                            Date asOfDateTime)
            throws ServerException {
        return mgmt.compareDatastreamChecksum(context, pid, dsID, asOfDateTime);
    }

    /**
     * {@inheritDoc}
     */
    public InputStream export(Context context,
                              String pid,
                              String format,
                              String exportContext,
                              String encoding) throws ServerException {
        return mgmt.export(context, pid, format, exportContext, encoding);
    }

    /**
     * {@inheritDoc}
     */
    public Datastream getDatastream(Context context,
                                    String pid,
                                    String datastreamID,
                                    Date asOfDateTime) throws ServerException {
        return mgmt.getDatastream(context, pid, datastreamID, asOfDateTime);
    }

    /**
     * {@inheritDoc}
     */
    public Datastream[] getDatastreamHistory(Context context,
                                             String pid,
                                             String datastreamID)
            throws ServerException {
        return mgmt.getDatastreamHistory(context, pid, datastreamID);
    }

    /**
     * {@inheritDoc}
     */
    public Datastream[] getDatastreams(Context context,
                                       String pid,
                                       Date asOfDateTime,
                                       String dsState) throws ServerException {
        return mgmt.getDatastreams(context, pid, asOfDateTime, dsState);
    }

    /**
     * {@inheritDoc}
     */
    public String[] getNextPID(Context context, int numPIDs, String namespace)
            throws ServerException {
        return mgmt.getNextPID(context, numPIDs, namespace);
    }

    /**
     * {@inheritDoc}
     */
    public InputStream getObjectXML(Context context, String pid, String encoding)
            throws ServerException {
        return mgmt.getObjectXML(context, pid, encoding);
    }

    /**
     * {@inheritDoc}
     */
    public RelationshipTuple[] getRelationships(Context context,
                                                String pid,
                                                String relationship)
            throws ServerException {
        return mgmt.getRelationships(context, pid, relationship);
    }

    /**
     * {@inheritDoc}
     */
    public InputStream getTempStream(String id) throws ServerException {
        return mgmt.getTempStream(id);
    }

    /**
     * {@inheritDoc}
     */
    public String ingest(Context context,
                         InputStream serialization,
                         String logMessage,
                         String format,
                         String encoding,
                         String pid) throws ServerException {
        return mgmt.ingest(context,
                           serialization,
                           logMessage,
                           format,
                           encoding,
                           pid);
    }

    /**
     * {@inheritDoc}
     */
    public Date modifyDatastreamByReference(Context context,
                                            String pid,
                                            String datastreamID,
                                            String[] altIDs,
                                            String dsLabel,
                                            String mimeType,
                                            String formatURI,
                                            String dsLocation,
                                            String checksumType,
                                            String checksum,
                                            String logMessage)
            throws ServerException {
        return mgmt.modifyDatastreamByReference(context,
                                                pid,
                                                datastreamID,
                                                altIDs,
                                                dsLabel,
                                                mimeType,
                                                formatURI,
                                                dsLocation,
                                                checksumType,
                                                checksum,
                                                logMessage);
    }

    /**
     * {@inheritDoc}
     */
    public Date modifyDatastreamByValue(Context context,
                                        String pid,
                                        String datastreamID,
                                        String[] altIDs,
                                        String dsLabel,
                                        String mimeType,
                                        String formatURI,
                                        InputStream dsContent,
                                        String checksumType,
                                        String checksum,
                                        String logMessage) throws ServerException {
        return mgmt.modifyDatastreamByValue(context,
                                            pid,
                                            datastreamID,
                                            altIDs,
                                            dsLabel,
                                            mimeType,
                                            formatURI,
                                            dsContent,
                                            checksumType,
                                            checksum,
                                            logMessage);
    }

    /**
     * {@inheritDoc}
     */
    public Date modifyObject(Context context,
                             String pid,
                             String state,
                             String label,
                             String ownerId,
                             String logMessage) throws ServerException {

        return mgmt.modifyObject(context,
                                 pid,
                                 state,
                                 label,
                                 ownerId,
                                 logMessage);
    }

    /**
     * {@inheritDoc}
     */
    public Date[] purgeDatastream(Context context,
                                  String pid,
                                  String datastreamID,
                                  Date startDT,
                                  Date endDT,
                                  String logMessage) throws ServerException {
        return mgmt.purgeDatastream(context,
                                    pid,
                                    datastreamID,
                                    startDT,
                                    endDT,
                                    logMessage);
    }

    /**
     * {@inheritDoc}
     */
    public Date purgeObject(Context context,
                            String pid,
                            String logMessage) throws ServerException {
        return mgmt.purgeObject(context, pid, logMessage);
    }

    /**
     * {@inheritDoc}
     */
    public boolean purgeRelationship(Context context,
                                     String pid,
                                     String relationship,
                                     String object,
                                     boolean isLiteral,
                                     String datatype) throws ServerException {
        return mgmt.purgeRelationship(context,
                                      pid,
                                      relationship,
                                      object,
                                      isLiteral,
                                      datatype);
    }

    /**
     * {@inheritDoc}
     */
    public String putTempStream(Context context, InputStream in)
            throws ServerException {
        return mgmt.putTempStream(context, in);
    }

    /**
     * {@inheritDoc}
     */
    public Date setDatastreamState(Context context,
                                   String pid,
                                   String dsID,
                                   String dsState,
                                   String logMessage) throws ServerException {
        return mgmt.setDatastreamState(context, pid, dsID, dsState, logMessage);
    }

    /**
     * {@inheritDoce}
     */
    public Date setDatastreamVersionable(Context context,
                                         String pid,
                                         String dsID,
                                         boolean versionable,
                                         String logMessage)
            throws ServerException {
        return mgmt.setDatastreamVersionable(context,
                                             pid,
                                             dsID,
                                             versionable,
                                             logMessage);
    }

    /**
     * Build a proxy chain as configured by the module parameters.
     *
     * @param m The concrete Management implementation to wrap.
     * @return A proxy chain for Management
     * @throws ModuleInitializationException
     */
    private Management getProxyChain(Management m)
            throws ModuleInitializationException {
        try {
            invocationHandlers = getInvocationHandlers();
            return (Management) ProxyFactory.getProxy(m, invocationHandlers);
        } catch (Exception e) {
            throw new ModuleInitializationException(e.getMessage(), getRole());
        }
    }

    /**
     * Get an <code>Array</code> of <code>AbstractInvocationHandler</code>s. The
     * ordering is ascending alphabetical, determined by the module parameter
     * names that begin with the string "decorator", e.g. "decorator1",
     * "decorator2".
     *
     * @return An array InvocationHandlers
     */
    private AbstractInvocationHandler[] getInvocationHandlers() throws Exception {
        List<String> pNames = new ArrayList<String>();
        Iterator<String> it = parameterNames();
        String param;
        while (it.hasNext()) {
            param = it.next();
            if (param.startsWith("decorator")) {
                pNames.add(param);
            }
        }
        Collections.sort(pNames);

        AbstractInvocationHandler[] invocationHandlers = new AbstractInvocationHandler[pNames.size()];
        for (int i = 0; i < pNames.size(); i++) {
            invocationHandlers[i] =
                    getInvocationHandler(getParameter(pNames.get(i)));
        }

        return invocationHandlers;
    }

    private static AbstractInvocationHandler getInvocationHandler(String invocationHandler)
            throws Exception {
        if (invocationHandler == null) {
            return null;
        }
        Class<?> c = Class.forName(invocationHandler);
        return (AbstractInvocationHandler) c.newInstance();
    }
}