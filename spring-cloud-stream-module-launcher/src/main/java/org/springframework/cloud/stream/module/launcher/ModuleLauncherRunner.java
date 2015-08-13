/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.module.launcher;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Spring boot {@link ApplicationRunner} that triggers {@link ModuleLauncher} to launch the modules.
 *
 * @author Marius Bogoevici
 * @author Ilayaperumal Gopinathan
 */
@Component
@EnableConfigurationProperties(ModuleLauncherProperties.class)
public class ModuleLauncherRunner implements ApplicationRunner {

	private final static Log log = LogFactory.getLog(ModuleLauncherRunner.class);

	@Autowired
	private ModuleLauncherProperties moduleLauncherProperties;

	@Autowired
	private ModuleLauncher moduleLauncher;

	@Override
	public void run(ApplicationArguments applicationArguments) throws Exception {
		String[] launchedModules = moduleLauncherProperties.getModules();
		if (log.isInfoEnabled()) {
			log.info("Launching: "
					+ StringUtils.arrayToCommaDelimitedString(launchedModules)
					+ " with arguments: "
					+ StringUtils.arrayToCommaDelimitedString(applicationArguments
							.getSourceArgs()));
		}
		this.moduleLauncher.launch(launchedModules, applicationArguments.getSourceArgs());
	}
}
