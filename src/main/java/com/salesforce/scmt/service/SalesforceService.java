/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 *  Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 *  Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 *  Neither the name of Salesforce.com nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.scmt.service;

import static java.lang.System.getenv;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import com.salesforce.scmt.model.DeployException;
import com.salesforce.scmt.model.DeployResponse;
import com.salesforce.scmt.utils.JsonUtil;
import com.salesforce.scmt.utils.SalesforceConstants;
import com.salesforce.scmt.utils.SalesforceUtil;
import com.salesforce.scmt.utils.Utils;
import com.sforce.async.AsyncApiException;
import com.sforce.async.BulkConnection;
import com.sforce.async.ConcurrencyMode;
import com.sforce.async.ContentType;
import com.sforce.async.JobInfo;
import com.sforce.async.JobStateEnum;
import com.sforce.async.OperationEnum;
import com.sforce.soap.metadata.*;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.SaveResult;
import com.sforce.soap.partner.UpsertResult;
import com.sforce.soap.partner.fault.UnexpectedErrorFault;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;

public final class SalesforceService
{
    private MetadataConnection _mConn;
    private PartnerConnection _pConn;
    private BulkConnection _bConn;
    private List<Metadata> _metadata;
    private String _serverUrl;
    private String _sessionId;
    private Map<String, String> _queues;
    private static String SALESFORCE_TRACE_METADATA = "SALESFORCE_TRACE_METADATA";
    private static String SALESFORCE_TRACE_PARTNER = "SALESFORCE_TRACE_PARTNER";
    private static String SALESFORCE_TRACE_BULK = "SALESFORCE_TRACE_BULK";

    private boolean _auditFieldsEnabled = false;	

    /**
     * Private constructor for utility class. TODO: Consider making this not so 'static-y'
     */
    public SalesforceService(String serverUrl, String sessionId)
    {
        this.setServerUrl(serverUrl);
        this.setSessionId(sessionId);
    }

    public void setServerUrl(String url)
    {
        // API needs an endpoint URL like this:
        // https://na30.salesforce.com/services/Soap/u/35.0/00D36000000H5z4
        // But the '$Api.Partner_Server_URL_350' I am using in VF returns a value like this:
        // https://c.na30.visual.force.com/services/Soap/u/35.0/00D36000000H5z4
        // When the "My Domain" feature is enabled, the URL looks like this:
        // https://datepickertest--sfdevpro1--c.cs45.visual.force.com/services/Soap/u/36.0/00D8A0000008py4
    	// https://suncommon-/na37.salesforce.com/services/Soap/u/36.0/00DU0000000xxxx 
    	System.out.println("url "+url);
    	
        _serverUrl = url.replaceFirst("\\/\\/.*c\\.", "\\/\\/").replaceFirst("\\/.*?scmt\\.", "\\/\\/").replaceFirst("visual\\.", "sales");        
    }

    public String getServerUrl()
    {
        return _serverUrl;
    }

    public void setQueues() throws Exception
    {
    	_queues = SalesforceUtil.getQueueName2Id(this);
    }
    
    public Map<String, String> getQueues() throws Exception
    {
    	if(_queues == null)
    	{
    		setQueues();
    	}
    	return _queues;
    }
    
    public String getMetadataUrl()
    {
        // Metadata API needs an endpoint URL like this:
        // https://na30.salesforce.com/services/Soap/m/35.0/00D36000000H5z4
        return _serverUrl.replaceFirst("\\/u\\/", "\\/m\\/");
    }

    public String getBulkEndpoint()
    {
        // Change serverURL to bulk api
        return _serverUrl.substring(0, _serverUrl.indexOf("Soap/")) + "async/36.0";
    }

    public void setSessionId(String sessionId)
    {
        _sessionId = sessionId;
    }

    public String getSessionId()
    {
        return _sessionId;
    }

    private static ConnectorConfig getConnectorConfig(String serverUrl, String sessionId)
    {
        ConnectorConfig config = new ConnectorConfig();
        config.setServiceEndpoint(serverUrl);
        config.setSessionId(sessionId);
        config.setCompression(true);
        return config;
    }

    private MetadataConnection getMetadataConnection()
    {
        return _mConn;
    }

    private void setMetadataConnection(MetadataConnection mConn)
    {
        this._mConn = mConn;
    }

    private BulkConnection getBulkConnection()
    {
        return _bConn;
    }

    private void setBulkConnection(BulkConnection bConn)
    {
        this._bConn = bConn;
    }

    private PartnerConnection getPartnerConnection()
    {
        return _pConn;
    }

    private void setPartnerConnection(PartnerConnection pConn)
    {
        this._pConn = pConn;
    }

    private void createMetadataConnection()
        throws ConnectionException, AsyncApiException
    {
        Utils.log("SalesforceService::createMetadataConnection() entered");
        // check if connection has already been created
        if (getMetadataConnection() != null)
        {
            // connection already created
            return;
        }

        ConnectorConfig config = getConnectorConfig(getServerUrl(), getSessionId());
        config.setServiceEndpoint(getMetadataUrl());

        // check if tracing is enabled
        if (getenv(SALESFORCE_TRACE_METADATA) != null && getenv(SALESFORCE_TRACE_METADATA).equalsIgnoreCase("1"))
        {
            // set this to true to see HTTP requests and responses on stdout
            config.setTraceMessage(true);
            config.setPrettyPrintXml(true);

            // this should only be false when doing debugging.
            config.setCompression(false);
        }

        setMetadataConnection(new MetadataConnection(config));

        // allow partial success
        getMetadataConnection().setAllOrNoneHeader(false);

        // print the endpoint
        Utils.log(
            "\n\tSession ID:            " + getSessionId() +
            "\n\tEndpoint:              " + getServerUrl() +
            "\n\tConnection Session ID: " + _mConn.getConfig().getSessionId() +
            "\n\tAuth Endpoint:         " + _mConn.getConfig().getAuthEndpoint());
    }

    private void createPartnerConnection() throws ConnectionException
    {
        // check if connection has already been created
        if (getPartnerConnection() != null)
        {
            // connection already created
            return;
        }

        // print the info we will use to build the connection
        Utils.log("SalesforceService::createPartnerConnection() entered" + "\n\tSession ID:       "
            + getSessionId() + "\n\tPartner Endpoint: " + getServerUrl());

        // create partner connector configuration
        ConnectorConfig partnerConfig = getConnectorConfig(getServerUrl(), getSessionId());

        // check if tracing is enabled
        if (getenv(SALESFORCE_TRACE_PARTNER) != null && getenv(SALESFORCE_TRACE_PARTNER).equalsIgnoreCase("1"))
        {
            // set this to true to see HTTP requests and responses on stdout
            partnerConfig.setTraceMessage(true);
            partnerConfig.setPrettyPrintXml(true);

            // this should only be false when doing debugging.
            partnerConfig.setCompression(false);
        }

        setPartnerConnection(new PartnerConnection(partnerConfig));

        // allow partial success
        getPartnerConnection().setAllOrNoneHeader(false);

        // truncate fields that are too long
        getPartnerConnection().setAllowFieldTruncationHeader(true);
    }

    private void createBulkConnection() throws AsyncApiException
    {
        // check if connection has already been created
        if (getBulkConnection() != null)
        {
            // connection already created
            return;
        }

        // print the info we will use to build the connection
        Utils.log("SalesforceService::createBulkConnection() entered" + "\n\tSession ID:    " + getSessionId()
            + "\n\tBulk Endpoint: " + getBulkEndpoint());

        // create partner connector configuration
        ConnectorConfig bulkConfig = getConnectorConfig(getServerUrl(), getSessionId());
        bulkConfig.setSessionId(getSessionId());
        bulkConfig.setRestEndpoint(getBulkEndpoint());
        bulkConfig.setCompression(true);

        // check if tracing is enabled
        if (getenv(SALESFORCE_TRACE_BULK) != null && getenv(SALESFORCE_TRACE_BULK).equalsIgnoreCase("1"))
        {
            // set this to true to see HTTP requests and responses on stdout
            bulkConfig.setTraceMessage(true);
            bulkConfig.setPrettyPrintXml(true);

            // this should only be false when doing debugging.
            bulkConfig.setCompression(false);
        }

        setBulkConnection(new BulkConnection(bulkConfig));
    }

    public PermissionSet getPermissionSet(String name) throws Exception
    {
        createMetadataConnection();
        ReadResult readResult = getMetadataConnection().readMetadata("PermissionSet", new String[] { name });
        Metadata[] mdInfo = readResult.getRecords();
        return (PermissionSet) mdInfo[0];
    }

    /**
     * Add a list of Salesforce Metadata API custom fields to be created.
     * 
     * @param customFields
     *            The list of custom fields to be created.
     */
    public void addCustomFields(List<Metadata> customFields)
    {
        // check if the metadata list needs to be initialized
        if (_metadata == null)
        {
            _metadata = new ArrayList<Metadata>();
        }
        // add the passed list of metadata to the internal list
        _metadata.addAll(customFields);
    }

    /**
     * Add a list of Salesforce Metadata API queues to be created.
     * 
     * @param queues
     *            The list of queues to be created.
     */
    public void addQueues(List<Queue> queues)
    {
        // check if the custom fields list needs to be initialized
        if (_metadata == null)
        {
            _metadata = new ArrayList<Metadata>();
        }

        // add the passed list of custom fields to the internal list

        _metadata.addAll(queues);

        // TODO: I still don't know if this is the right place to do this. What happens if we move queues twice?
        // Create Queue "Unassigned" for Case, used for Case upsert;
        Queue unassignedQueue = new Queue();
        unassignedQueue.setName(SalesforceConstants.QueueUnassigned);
        unassignedQueue.setFullName(SalesforceConstants.QueueUnassigned);
        QueueSobject[] qs = new QueueSobject[1];
        QueueSobject q = new QueueSobject();
        q.setSobjectType(SalesforceConstants.OBJ_CASE);
        qs[0] = q;
        unassignedQueue.setQueueSobject(qs);

        // add unassignedQueue to srcs list of queues
        _metadata.add(unassignedQueue);
    }

    /**
     * Add a list of Salesforce Metadata API DataCategory Groups to be created.
     * 
     * @param queues
     *            The list of data categories to be created.
     */
    public void addCategories(List<DataCategoryGroup> categories)
    {
        // check if the custom fields list needs to be initialized
        if (_metadata == null)
        {
            _metadata = new ArrayList<Metadata>();
        }

        // add the passed list of custom fields to the internal list
        _metadata.addAll(categories);
    }

    public DeployResponse deploy()
        throws ConnectionException, DeployException, AsyncApiException
    {
        // declare the return variable
        DeployResponse dr = new DeployResponse();

        // check if the metadata queue is empty
        if (_metadata != null && !_metadata.isEmpty())
        {
            // ensure a metadata connection has been initialized
            createMetadataConnection();

            // declare the variable that will hold each batch of metadata
            List<Metadata> batch = new ArrayList<>();

            // loop through the items and every 10 items call create
            for (Metadata m : _metadata)
            {
                // add the item
                batch.add(m);

                // check if we have reached 10 items
                if (batch.size() == 10)
                {
                    // call the 'createMetadata' API
                    dr = handleMetadataResponse(dr,
                        getMetadataConnection().upsertMetadata(batch.toArray(new Metadata[] {})));

                    // clear the batch
                    batch.clear();
                }
            }

            // check there was a remainder of metadata
            if (!batch.isEmpty())
            {
                // call the 'createMetadata' API

                dr = handleMetadataResponse(dr,
                    getMetadataConnection().upsertMetadata(batch.toArray(new Metadata[] {})));

                // clear the batch
                batch.clear();
            }
        }

        // clear out the metadata items
        // TODO: (just successful items?)
        if (_metadata != null)
        {
            _metadata.clear();
        }

        return dr;
    }

    private static DeployResponse handleMetadataResponse(DeployResponse dr,
        com.sforce.soap.metadata.UpsertResult[] results) throws DeployException
    {
        for (com.sforce.soap.metadata.UpsertResult result : results)
        {
            if (result.isSuccess())
            {
                dr.incrementSuccessCount();
            }
            else
            {
                for (com.sforce.soap.metadata.Error e : result.getErrors())
                {
                    dr.addError(String.format("Status Code: [%s]\nMessage: [%s]\nFields: [%s]\n",
                        e.getStatusCode().name(), e.getMessage(), String.join(", ", e.getFields())));
                    dr.incrementErrorCount();
                }
            }
        }

        return dr;
    }

    private static DeployResponse handleInsertResponse(DeployResponse dr, SaveResult[] results) throws DeployException
    {
        boolean foundErrors = false;
        for (SaveResult sr : results)
        {
            if (sr.getSuccess())
            {
                // Utils.log("'insert' was successful! [" + ur.getId() + "]");
                dr.incrementSuccessCount();
            }
            else
            {
                for (com.sforce.soap.partner.Error e : sr.getErrors())
                {
                    dr.addError(String.format("Status Code: [%s]\nMessage: [%s]\n%s\n", e.getStatusCode().name(),
                        e.getMessage(), (e.getFields() == null || e.getFields().length == 0 ? ""
                            : String.format("Fields: [%s]", String.join(", ", e.getFields())))));
                    dr.incrementErrorCount();
                }
                foundErrors = true;
            }
        }

        if (foundErrors)
        {
            Utils.log("'insert' resulted in errors! See log.");
        }

        return dr;
    }

    private static DeployResponse handleUpsertResponse(DeployResponse dr, UpsertResult[] results) throws DeployException
    {
        boolean foundErrors = false;
        for (UpsertResult ur : results)
        {
            if (ur.getSuccess())
            {
                // Utils.log("'upsert' was successful! [" + ur.getId() + "]");
                dr.incrementSuccessCount();
            }
            else
            {
                for (com.sforce.soap.partner.Error e : ur.getErrors())
                {
                    dr.addError(String.format("Status Code: [%s]\nMessage: [%s]\n%s\n", e.getStatusCode().name(),
                        e.getMessage(), (e.getFields() == null || e.getFields().length == 0 ? ""
                            : String.format("Fields: [%s]", String.join(", ", e.getFields())))));
                    dr.incrementErrorCount();
                }
                foundErrors = true;
            }
        }

        if (foundErrors)
        {
            Utils.log("'upsert' resulted in errors! See log.");
        }

        return dr;
    }

    public DeployResponse insertData(List<SObject> sobjects)
        throws ConnectionException, DeployException, AsyncApiException
    {
        return insertData(sobjects, false, null);
    }

    public DeployResponse insertData(List<SObject> sobjects, boolean allOrNone,
        List<SaveResult> saveResults) throws ConnectionException, DeployException, AsyncApiException
    {
        DeployResponse dr = new DeployResponse();

        // ensure the partner connection has been initialized
        createPartnerConnection();

        // check if the SObject queue is empty
        if (sobjects == null || sobjects.isEmpty())
        {
            Utils.log("An empty list of SObject was passed to insertData()!");
        }
        else
        {
            Utils.log(String.format("Inserting %d records.", sobjects.size()));

            // check if we want AllOrNone header
            if (allOrNone)
            {
                _pConn.setAllOrNoneHeader(true);
            }

            // insert the records
            com.sforce.soap.partner.SaveResult[] SRs = _pConn.create(sobjects.toArray(new SObject[] {}));

            // reset AllOrNone header
            if (allOrNone)
            {
                _pConn.setAllOrNoneHeader(false);
            }

            // check if we want to return the save results
            if (saveResults != null)
            {
                // loop through the save results and add them to the list
                // I can't just assign SRs to saveResults because it changes the
                // memory address of saveResult and doesn't return the data.
                for (SaveResult sr : SRs)
                {
                    saveResults.add(sr);
                }
            }

            // process the results and log errors
            dr = SalesforceService.handleInsertResponse(dr, SRs);
        }
        return dr;
    }

    public DeployResponse upsertData(String IdField, List<SObject> sobjects)
        throws ConnectionException, DeployException, AsyncApiException
    {
        DeployResponse dr = new DeployResponse();

        // ensure the partner connection has been initialized
        createPartnerConnection();

        // check if the SObject queue is empty
        if (sobjects == null || sobjects.isEmpty())
        {
            Utils.log("An empty list of SObject was passed to upsertData()!");
        }
        else
        {
            Utils.log(String.format("Upserting %d records with Id field [%s].", +sobjects.size(), IdField));

            // upsert the records
            com.sforce.soap.partner.UpsertResult[] URs = _pConn.upsert(IdField,
                sobjects.toArray(new SObject[] {}));

            // process the results and log errors
            dr = SalesforceService.handleUpsertResponse(dr, URs);
        }
        return dr;
    }

    // Bulk API example code:
    // https://developer.salesforce.com/docs/atlas.en-us.api_asynch.meta/api_asynch/asynch_api_code_walkthrough.htm

    public String createBulkJob(String sobjectType, String upsertField,
        OperationEnum op) throws AsyncApiException
    {
        Utils.log("[BULK] Creating Bulk Job:" + "\n\tObject:       [" + sobjectType + "]" + "\n\tUnique Field: ["
            + upsertField + "]" + "\n\tOperation:    [" + op + "]");

        // create a connection
        createBulkConnection();

        // create batch job
        JobInfo job = new JobInfo();
        job.setObject(sobjectType);
        job.setOperation(op);
        job.setConcurrencyMode(ConcurrencyMode.Serial);
        // JSON available in Spring '16
        job.setContentType(ContentType.JSON);
        if (upsertField != null)
        {
            job.setExternalIdFieldName(upsertField);
        }

        // create the job
        job = _bConn.createJob(job);

        Utils.log("Job created: " + job.getId());
        return job.getId();
    }

    public void addBatchToJob(String jobId, List<Map<String, Object>> records)
        throws UnsupportedEncodingException, AsyncApiException
    {
        Utils.log("[BULK] Adding [" + records.size() + "] records to job [" + jobId + "].");

        // convert the records into a byte stream
        ByteArrayInputStream jsonStream = new ByteArrayInputStream(JsonUtil.toJson(records).getBytes("UTF-8"));

        JobInfo job = new JobInfo();
        job.setId(jobId);
        job.setContentType(ContentType.JSON);

        // submit a batch to the job
        _bConn.createBatchFromStream(job, jsonStream);
    }

    public void closeBulkJob(String jobId) throws AsyncApiException
    {
        Utils.log("[BULK] Closing Bulk Job: [" + jobId + "]");

        JobInfo job = new JobInfo();
        job.setId(jobId);
        job.setState(JobStateEnum.Closed);
        job.setContentType(ContentType.JSON);
        // _bConn.updateJob(job, ContentType.JSON);

        // unclear if I can use this
        _bConn.closeJob(jobId);
    }

    /*
     * Check to see if a valid job exists in Salesforce Valid job is less than 12 hours old
     */
    public boolean createNewJob(String jobId) throws AsyncApiException
    {
        JobInfo job = _bConn.getJobStatus(jobId, ContentType.JSON);
        Utils.log("[BULK] Getting Bulk Job Status: [" + jobId + "]");
        Calendar cal = job.getCreatedDate();

        // If current time is more than the time since the job was created, true
        return ((System.currentTimeMillis() - cal.getTime().getTime()) > SalesforceConstants.JOB_LIFE);
    }

    public List<SObject> query(String query)
        throws ConnectionException, UnexpectedErrorFault
    {
        return query(query, true);
    }

    public List<SObject> query(String query, boolean queryMore)
        throws ConnectionException, UnexpectedErrorFault
    {
        Utils.log("[QUERY] " + query);
        // create connection
        createPartnerConnection();

        // initialize results list
        List<SObject> results = new ArrayList<SObject>();

        // run the query
        QueryResult qr = _pConn.query(query);

        // put the results into my return list
        results = Arrays.asList(qr.getRecords());

        // check if the query is done
        while (queryMore && !qr.isDone())
        {
            Utils.log("[QUERY] Calling 'queryMore()' to retrieve more results...");

            // call 'queryMore' to retrieve more results
            qr = _pConn.queryMore(qr.getQueryLocator());

            // add results to list
            results.addAll(Arrays.asList(qr.getRecords()));
        }

        Utils.log(String.format("[QUERY] Retrieved [%d] records.", results.size()));

        // return the results
        return results;
    }

    public void setAuditFieldsEnabled(Boolean valueOf)
    {
        this._auditFieldsEnabled = valueOf;
    }

    public Boolean getAuditFieldsEnabled()
    {
        return _auditFieldsEnabled;
    }
}