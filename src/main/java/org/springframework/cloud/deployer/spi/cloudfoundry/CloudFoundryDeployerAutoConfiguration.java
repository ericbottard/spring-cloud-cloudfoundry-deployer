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

import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryConnectionProperties.CLOUDFOUNDRY_PROPERTIES;

import com.github.zafarkhaja.semver.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Creates a {@link CloudFoundryAppDeployer}
 *
 * @author Eric Bottard
 * @author Ben Hale
 */
@Configuration
@EnableConfigurationProperties
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
public class CloudFoundryDeployerAutoConfiguration {

	private static final Version CF_TASKS_INFLECTION_POINT = Version.forIntegers(2, 63, 0);

	private static final Logger logger = LoggerFactory.getLogger(CloudFoundryDeployerAutoConfiguration.class);

	@Bean
	@ConditionalOnMissingBean(name = "appDeploymentProperties")
	public CloudFoundryDeploymentProperties appDeploymentProperties() {
		return defaultSharedDeploymentProperties();
	}

	@Bean
	@ConditionalOnMissingBean(name = "taskDeploymentProperties")
	public CloudFoundryDeploymentProperties taskDeploymentProperties() {
		return defaultSharedDeploymentProperties();
	}

	@Bean
	@ConfigurationProperties(prefix = CLOUDFOUNDRY_PROPERTIES)
	public CloudFoundryDeploymentProperties defaultSharedDeploymentProperties() {
		return new CloudFoundryDeploymentProperties();
	}

	@Bean
	@ConditionalOnMissingBean
	@ConfigurationProperties(prefix = CloudFoundryConnectionProperties.CLOUDFOUNDRY_PROPERTIES)
	public CloudFoundryConnectionProperties cloudFoundryConnectionProperties() {
		return new CloudFoundryConnectionProperties();
	}

	@Bean
	@ConditionalOnMissingBean(AppDeployer.class)
	public AppDeployer appDeployer(AppNameGenerator applicationNameGenerator) {
		return new CloudFoundryAppDeployer(applicationNameGenerator);
	}

	@Bean
	@ConditionalOnMissingBean(AppNameGenerator.class)
	public AppNameGenerator appDeploymentCustomizer() {
		return new CloudFoundryAppNameGenerator(appDeploymentProperties());
	}

	@Bean
	@ConditionalOnMissingBean(TaskLauncher.class)
	public TaskLauncher taskLauncher() {
		return new CloudFoundry2630AndLaterTaskLauncher();
	}

	@Bean
	@ConfigurationPropertiesBinding
	public DurationConverter durationConverter() {
		return new DurationConverter();
	}

}
