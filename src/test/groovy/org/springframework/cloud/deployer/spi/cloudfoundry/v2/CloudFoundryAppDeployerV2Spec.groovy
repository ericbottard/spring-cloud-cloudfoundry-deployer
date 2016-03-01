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

package org.springframework.cloud.deployer.spi.cloudfoundry.v2

import org.cloudfoundry.client.lib.domain.CloudApplication
import org.cloudfoundry.spring.client.SpringCloudFoundryClient
import org.springframework.cloud.deployer.spi.AppDefinition
import org.springframework.cloud.deployer.spi.AppDeploymentId
import org.springframework.cloud.deployer.spi.AppDeploymentRequest
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryAppDeployProperties
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryAppDeployer
import org.springframework.core.io.Resource
import spock.lang.Specification
/**
 * @author Greg Turnquist
 */
class CloudFoundryAppDeployerV2Spec extends Specification {

	SpringCloudFoundryClient client

	Resource resource

	def setup() {
		resource = Mock(Resource)

		def host = 'api.white.springapps.io'
		def skipSslValidation = true
		def username = 'gturnquist@pivotal.io'
		def password = 'Delta1143'

		client = SpringCloudFoundryClient.builder()
				.host(host)
				.username(username)
				.password(password)
				.skipSslValidation(skipSslValidation)
				.build()
	}

	def "should handle deploying a non-existent app"() {
		given:
		CloudFoundryAppDeployProperties properties = new CloudFoundryAppDeployProperties(
				organization: 'spinnaker',
				space: 'production'
		)
		CloudFoundryAppDeployerV2 deployer = new CloudFoundryAppDeployerV2(properties, client)

		def appName = 'my-cool-app'

		when:
		def results = deployer.deploy(new AppDeploymentRequest(
				new AppDefinition(appName, 'my-cool-group', Collections.EMPTY_MAP),
				resource))

		then:
		results.name == appName
		results.group == 'my-cool-group'
		results.properties == [:]

		0 * resource._
	}


	def "should handle undeploying a non-existent app"() {
		given:
		CloudFoundryAppDeployProperties properties = new CloudFoundryAppDeployProperties()
		CloudFoundryAppDeployerV2 deployer = new CloudFoundryAppDeployerV2(properties, client)

		def appName = 'my-cool-app'

		when:
		deployer.undeploy(new AppDeploymentId('my-cool-group', appName))

		then:
		IllegalStateException e = thrown()
		e.message == "${appName} is not deployed."

		0 * client._
	}

	def "should fail when deploying an already-existing app"() {
		given:
		CloudFoundryAppDeployProperties properties = new CloudFoundryAppDeployProperties()
		CloudFoundryAppDeployer deployer = new CloudFoundryAppDeployer(properties, client)

		def appName = 'my-cool-app'

		when:
		deployer.deploy(new AppDeploymentRequest(
			new AppDefinition(appName, 'my-cool-group', Collections.EMPTY_MAP),
			resource))

		then:
		IllegalStateException e = thrown()
		e.message == "${appName} is already deployed."

		1 * client.login()
		1 * client.getApplication(appName) >> {
			new CloudApplication(null, appName)
		}
		0 * client._

		0 * resource._
	}

}