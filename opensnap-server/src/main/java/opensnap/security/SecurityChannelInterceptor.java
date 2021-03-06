/*
 * Copyright 2002-2014 the original author or authors.
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

package opensnap.security;

import opensnap.domain.User;
import opensnap.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptorAdapter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.*;
import java.util.concurrent.ExecutionException;

@Component
public class SecurityChannelInterceptor extends ChannelInterceptorAdapter {

	private Map<String, Object> securityDefinitions;
	private UserService userService;
	private static final Logger logger = LoggerFactory.getLogger(SecurityChannelInterceptor.class);

	@Autowired
	public SecurityChannelInterceptor(UserService userService) {
		this.userService = userService;
	}

	public void loadConfiguration(String filename) {
		YamlMapFactoryBean factory = new YamlMapFactoryBean();
		factory.setResources(new ClassPathResource[]{new ClassPathResource(filename)});
		this.securityDefinitions = factory.getObject();
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		UsernamePasswordAuthenticationToken authentication = (UsernamePasswordAuthenticationToken)message.getHeaders().get(SimpMessageHeaderAccessor.USER_HEADER);
		String destination = (String)message.getHeaders().get(SimpMessageHeaderAccessor.DESTINATION_HEADER);
		if((destination == null) || isAllowed(destination, authentication.getName())) {
			return message;
		}
		throw new AccessDeniedException("Message to destination " + destination + " not allowed for user " + authentication.getName());

	}

	protected boolean isAllowed(String destination, String username) {
		try {
			User  user = this.userService.getByUsername(username).get();
			if(user == null) {
				return false;
			}
			List<String> userRoles = user.getRoles();
			return browseMap(this.securityDefinitions, "/", destination, userRoles);
		} catch (ExecutionException|InterruptedException e) {
			logger.error(e.getMessage());
			return false;
		}

	}

	private boolean browseMap(Map<String, Object> map, String destinationRoot, String destination, List<String> userRoles) {
		for(String key : map.keySet()) {
			Object value = map.get(key);
			if (value instanceof String) {
				List<String> allowedRoles = Arrays.asList(((String) value).split(","));
				if (key.endsWith("*")) {
					if (destination.startsWith(destinationRoot + key.substring(0, key.length() - 1))) {
						return !Collections.disjoint(userRoles, allowedRoles);
					}
				} else if (destination.equals(destinationRoot + key)) {
					return !Collections.disjoint(userRoles, allowedRoles);
				}
			} else {
				Assert.isInstanceOf(Map.class, value);
				if(browseMap((Map)value, destinationRoot + key + "/", destination, userRoles)) {
					return true;
				}
			}
		}
		return false;
	}
}
