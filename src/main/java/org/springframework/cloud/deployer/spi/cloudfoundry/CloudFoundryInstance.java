/*
 * Copyright 2016 the original author or authors.
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
package org.springframework.cloud.deployer.spi.cloudfoundry;

import java.util.HashMap;
import java.util.Map;

import org.cloudfoundry.client.lib.domain.InstanceInfo;
import org.cloudfoundry.client.v3.applications.GetApplicationStatisticsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.deployer.spi.process.AppInstanceStatus;
import org.springframework.cloud.deployer.spi.process.DeploymentState;

/**
 * Wrap an {@link InstanceInfo} and provide convenient operations.
 *
 * @author Greg Turnquist
 */
public class CloudFoundryInstance implements AppInstanceStatus {

	private static final Logger logger = LoggerFactory.getLogger(CloudFoundryInstance.class);

	private final GetApplicationStatisticsResponse.Statistics statistics;

	public CloudFoundryInstance(GetApplicationStatisticsResponse.Statistics statistics) {

		this.statistics = statistics;
	}

	@Override
	public String getId() {
		return statistics.getHost() + ":" + statistics.getPort() + "(" + statistics.getIndex() + ")";
	}

	@Override
	public DeploymentState getState() {

		switch (statistics.getState()) {
			case "RUNNING":
				return DeploymentState.deployed;
			default:
				logger.warn("!!! We don't know how to handle " + statistics.getState());
				return DeploymentState.unknown;
		}
	}

	@Override
	public Map<String, String> getAttributes() {

		Map<String, String> attrs = new HashMap<>();

		attrs.put("disk", Long.toString(statistics.getDiskQuota()));
		attrs.put("fds", Long.toString(statistics.getFdsQuota()));
		attrs.put("memory", Long.toString(statistics.getMemoryQuota()));
		attrs.put("type", statistics.getType());

		return attrs;
	}
}
