/*
 * Copyright 2011 DTO Solutions, Inc. (http://dtosolutions.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/*
* ScriptPluginFileCopier.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: 4/5/11 10:11 AM
* 
*/
package com.dtolabs.rundeck.core.execution.service;

import com.dtolabs.rundeck.core.common.Framework;
import com.dtolabs.rundeck.core.common.INodeEntry;
import com.dtolabs.rundeck.core.dispatcher.DataContextUtils;
import com.dtolabs.rundeck.core.execution.ExecutionContext;
import com.dtolabs.rundeck.core.execution.impl.common.BaseFileCopier;
import com.dtolabs.rundeck.core.execution.workflow.steps.FailureReason;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepFailureReason;
import com.dtolabs.rundeck.core.execution.workflow.steps.node.NodeStepFailureReason;
import com.dtolabs.rundeck.core.plugins.BaseScriptPlugin;
import com.dtolabs.rundeck.core.plugins.PluginException;
import com.dtolabs.rundeck.core.plugins.ScriptPluginProvider;
import com.dtolabs.rundeck.core.utils.ScriptExecUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;


/**
 * ScriptPluginFileCopier wraps the execution of the script and supplies the FileCopier interface.
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
class ScriptPluginFileCopier extends BaseScriptPlugin implements DestinationFileCopier {
    @Override
    public boolean isAllowCustomProperties() {
        return false;
    }

    ScriptPluginFileCopier(final ScriptPluginProvider provider, final Framework framework) {
        super(provider, framework);
    }

    static void validateScriptPlugin(final ScriptPluginProvider plugin) throws PluginException {
        if (null == plugin.getScriptArgs()) {
            throw new PluginException(
                "no script-args defined for provider: " + plugin);
        }
    }

    /**
     * Copy inputstream
     */
    public String copyFileStream(final ExecutionContext executionContext, final InputStream inputStream,
                                 final INodeEntry node) throws FileCopierException {

        return copyFile(executionContext, null, inputStream, null, node, null, false);
    }

    /**
     * Copy existing file
     */
    public String copyFile(final ExecutionContext executionContext, final File file,
            final INodeEntry node) throws FileCopierException {
        return copyFile(executionContext, file, null, null, node, null, false);
    }

    /**
     * Copy string content
     */
    public String copyScriptContent(final ExecutionContext executionContext, final String s,
                                    final INodeEntry node) throws
                                                           FileCopierException {
        return copyFile(executionContext, null, null, s, node, null, false);
    }
    /**
     * Copy inputstream
     */
    public String copyFileStream(final ExecutionContext executionContext, final InputStream inputStream,
                                 final INodeEntry node, String destination) throws FileCopierException {

        return copyFile(executionContext, null, inputStream, null, node, destination, null == destination);
    }

    /**
     * Copy existing file
     */
    public String copyFile(final ExecutionContext executionContext, final File file, final INodeEntry node,
            final String destination) throws FileCopierException {
        return copyFile(executionContext, file, null, null, node, destination, null == destination);
    }

    /**
     * Copy string content
     */
    public String copyScriptContent(final ExecutionContext executionContext, final String s,
                                    final INodeEntry node, final String destination) throws
                                                           FileCopierException {
        return copyFile(executionContext, null, null, s, node, destination, null==destination);
    }


    static enum ScriptPluginFailureReason implements FailureReason {
        /**
         * Expected output from the plugin was missing
         */
        ScriptPluginFileCopierOutputMissing
    }

    /**
     * Internal copy method accepting file, inputstream or string
     */
    String copyFile(final ExecutionContext executionContext, final File file, final InputStream input,
            final String content, final INodeEntry node, String destination, boolean expandTokens) throws
                                                                 FileCopierException {
        final String pluginname = getProvider().getName();
        final Map<String, Map<String, String>> localDataContext = createScriptDataContext(
            executionContext.getFramework(),
            executionContext.getFrameworkProject(),
            executionContext.getDataContext());
        //add node context data
        localDataContext.put("node", DataContextUtils.nodeData(node));

        final File srcFile =
                expandTokens ?
                        //write the temp file and replace tokens in a script with values from the dataContext
                        BaseFileCopier.writeScriptTempFile(executionContext, file, input, content, node)
                        :
                        null != file ?
                                file
                                //write the temp file and do not replace tokens, the file will not be modified
                                : BaseFileCopier.writeTempFile(executionContext, file, input, content);

        String destFilePath=destination;
        //put file in a directory
        if (null != destFilePath && destFilePath.endsWith("/")) {
            destFilePath += srcFile.getName();
        }
        //add some more data context values to allow templatized script-copy attribute
        final HashMap<String, String> scptexec = new HashMap<String, String>();
        //set up the data context to include the local temp file
        scptexec.put("file", srcFile.getAbsolutePath());
        scptexec.put("destination", null != destFilePath ? destFilePath : "");
        localDataContext.put("file-copy", scptexec);

        final String[] finalargs = createScriptArgs(localDataContext);
        executionContext.getExecutionListener().log(3, "[" + getProvider().getName() + "] executing: " + Arrays.asList(
            finalargs));

        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            final int result = ScriptExecUtil.runLocalCommand(finalargs,
                                                    DataContextUtils.generateEnvVarsFromContext(localDataContext),
                                                    null,
                                                    byteArrayOutputStream,
                                                    System.err
            );
            executionContext.getExecutionListener().log(3, "[" + pluginname + "]: result code: " + result);
            if(result!=0){
                throw new FileCopierException("[" + pluginname + "]: external script failed with exit code: " + result,
                                              NodeStepFailureReason.NonZeroResultCode);
            }
        } catch (IOException e) {
            throw new FileCopierException(e.getMessage(), StepFailureReason.IOFailure);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FileCopierException(e.getMessage(), StepFailureReason.Interrupted);
        }

        if (null != destination) {
            return destFilePath;
        }

        //load string of output from outputstream
        final String output = byteArrayOutputStream.toString();
        if (null == output || output.length() < 1) {
            throw new FileCopierException("[" + pluginname + "]: No output from external script",
                                          ScriptPluginFailureReason.ScriptPluginFileCopierOutputMissing
            );
        }
        //TODO: require any specific format for the data?
        //look for first line of output
        final String[] split1 = output.split("(\\r?\\n)");
        if (split1.length < 1) {
            throw new FileCopierException("[" + pluginname + "]: No output from external script",
                                          ScriptPluginFailureReason.ScriptPluginFileCopierOutputMissing);
        }
        final String remotefilepath = split1[0];

        executionContext.getExecutionListener().log(3, "[" + pluginname + "]: result filepath: " + remotefilepath);

        return remotefilepath;
    }
}
