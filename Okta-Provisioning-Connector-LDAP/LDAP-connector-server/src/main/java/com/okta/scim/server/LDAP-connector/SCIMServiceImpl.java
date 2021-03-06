package com.okta.scim.server.LDAP.connector;

//IMPORTS!
import com.okta.scim.server.capabilities.UserManagementCapabilities;
import com.okta.scim.server.exception.DuplicateGroupException;
import com.okta.scim.server.exception.EntityNotFoundException;
import com.okta.scim.server.exception.OnPremUserManagementException;
import com.okta.scim.util.exception.InvalidDataTypeException;
import com.okta.scim.server.service.SCIMOktaConstants;
import com.okta.scim.server.service.SCIMService;
import com.okta.scim.util.model.Email;
import com.okta.scim.util.model.Membership;
import com.okta.scim.util.model.Name;
import com.okta.scim.util.model.PaginationProperties;
import com.okta.scim.util.model.SCIMFilter;
import com.okta.scim.util.model.SCIMFilterAttribute;
import com.okta.scim.util.model.SCIMFilterType;
import com.okta.scim.util.model.SCIMGroup;
import com.okta.scim.util.model.SCIMResource;
import com.okta.scim.util.model.SCIMGroupQueryResponse;
import com.okta.scim.util.model.SCIMUser;
import com.okta.scim.util.model.SCIMUserQueryResponse;
import com.okta.scim.util.model.PhoneNumber;

import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonNode;
import org.springframework.util.StringUtils;
import org.springframework.ldap.core.DistinguishedName;
import org.springframework.ldap.core.LdapRdn;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.configuration.ConfigurationException;

import java.util.HashSet;
import java.lang.Thread;
import java.lang.Object;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.NoSuchElementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.UnsupportedEncodingException;
import javax.naming.directory.SearchControls;
import javax.naming.directory.Attributes;
import javax.naming.directory.Attribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchResult;
import javax.naming.directory.InvalidAttributeValueException;
import javax.naming.NamingEnumeration;
import javax.naming.Context;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.NamingException;
import javax.annotation.PostConstruct;
import javax.xml.parsers.SAXParser;

public class SCIMServiceImpl implements SCIMService {
	//Absolute path for users.json set in the dispatcher-servlet.xml
	private String usersFilePath;
	//Absolute path for groups.json set in the dispatcher-servlet.xml
	private String groupsFilePath;
	//Ldap settings
	private String ldapBaseDn;
	private String ldapGroupDn;
	private String ldapUserDn;
	private String ldapUserPre;
	private String ldapGroupPre;
	private String ldapUserFilter;
	private String ldapGroupFilter;
	private String ldapInitialContextFactory;
	private String ldapUrl;
	private String ldapSecurityAuthentication;
	private String ldapSecurityPrincipal;
	private String ldapSecurityCredentials;
	private String[] ldapUserClass;
	private String[] ldapGroupClass;
	private List<String> usernameWhitelist;
	private boolean useWhitelist;
	private boolean useEntireUsername;
	private Map<String, String> ldapUserCore = new HashMap<String, String>();
	private Map<String, String[]> ldapUserCustom = new HashMap<String, String[]>();
	private Map<String, String> ldapGroupCore = new HashMap<String, String>();
	private String USER_RESOURCE = "user";
	private String GROUP_RESOURCE = "group";
	//This should be the name of the App you created. On the Okta URL for the App, you can find this name
	private String appName;
	//Field names for the custom properties
	private static final String CUSTOM_SCHEMA_PROPERTY_IS_ADMIN = "isAdmin";
	private static final String CUSTOM_SCHEMA_PROPERTY_IS_OKTA = "isOkta";
	private static final String CUSTOM_SCHEMA_PROPERTY_DEPARTMENT_NAME = "departmentName";
	//This should be the name of the Universal Directory schema you created. We are assuming this name is "custom"
	private static final String UD_SCHEMA_NAME = "custom";
	private static final Logger LOGGER = Logger.getLogger(SCIMServiceImpl.class);
	//properties file stored in /Okta-Provisioning-Connector-SDK/example-server/src/main/resources
	private static final String CONF_FILENAME = "connector.properties";

	private Map<String, SCIMUser> userMap = new HashMap<String, SCIMUser>();
	private Map<String, SCIMGroup> groupMap = new HashMap<String, SCIMGroup>();
	private int nextUserId;
	private int nextGroupId;
	private String userCustomUrn;
	private boolean useFilePersistence = true;
	private Hashtable env = new Hashtable(11);

	@PostConstruct
	public void afterCreation() throws Exception {
		LOGGER.info("[afterCreation] Initializing connector...");
		initLdapVars();
		LOGGER.info("[afterCreation] Imported config from connector.properties.");
		userCustomUrn = SCIMOktaConstants.CUSTOM_URN_PREFIX + appName + SCIMOktaConstants.CUSTOM_URN_SUFFIX + UD_SCHEMA_NAME;
		env.put(Context.INITIAL_CONTEXT_FACTORY, ldapInitialContextFactory);
		env.put(Context.PROVIDER_URL, ldapUrl);
		env.put(Context.SECURITY_AUTHENTICATION, ldapSecurityAuthentication);
		env.put(Context.SECURITY_PRINCIPAL, ldapSecurityPrincipal);
		env.put(Context.SECURITY_CREDENTIALS, ldapSecurityCredentials);
		nextUserId = 100;
		nextGroupId = 1000;
		LOGGER.info("Connector initialized and waiting for tasks.");
	}

	/**
	 * Helper function that pulls data from properties file.
	 *
	 * @throws ConfigurationException
	 */
	private void initLdapVars() throws ConfigurationException {
		Configuration config;
		String[] userCoreMapHolder;
		String[] userCustomMapHolder;
		String[] groupCoreMapHolder;
		Iterator<String> userCustomIt;
		Iterator<String> userCoreIt;
		Iterator<String> groupCoreIt;
		String customKey;
		String coreKey;
		String groupCoreKey;
		//TODO: Better way to do this mapping would be unidirectional mappings vs bidirectional
		//Connector -> LDAP and LDAP -> Connector would solve problem of changing mappings while
		//entries still exist in LDAP
		try {
			config = new PropertiesConfiguration(CONF_FILENAME);
			appName = config.getString("OPP.appName");
			ldapBaseDn = config.getString("ldap.baseDn");
			ldapGroupDn = config.getString("ldap.groupDn");
			ldapUserDn = config.getString("ldap.userDn");
			ldapGroupPre = config.getString("ldap.groupPre");
			ldapUserPre = config.getString("ldap.userPre");
			ldapUserFilter = config.getString("ldap.userFilter");
			ldapGroupFilter = config.getString("ldap.groupFilter");
			ldapInitialContextFactory = config.getString("ldap.initialContextFactory");
			ldapUrl = config.getString("ldap.url");
			ldapSecurityAuthentication = config.getString("ldap.securityAuthentication");
			ldapSecurityPrincipal = config.getString("ldap.securityPrincipal");
			ldapSecurityCredentials = config.getString("ldap.securityCredentials");
			ldapUserClass = config.getStringArray("ldap.userClass");
			ldapGroupClass = config.getStringArray("ldap.groupClass");
			userCustomIt = config.getKeys("OPP.userCustomMap");
			userCoreIt = config.getKeys("OPP.userCoreMap");
			groupCoreIt = config.getKeys("OPP.groupCoreMap");
			String[] whitelist = {"okta.com"};
			usernameWhitelist = Arrays.asList(config.getStringArray("OPP.whitelistForUsernames"));//Arrays.asList(whitelist);
			useWhitelist = Boolean.parseBoolean(config.getString("OPP.whitelist"));//false
			useEntireUsername = Boolean.parseBoolean(config.getString("ldap.useEntireUsername"));//true
			//TODO: can put this in a function or something, maybe
			while(userCustomIt.hasNext()) {
				customKey = userCustomIt.next();
				userCustomMapHolder = config.getStringArray(customKey);
				ldapUserCustom.put(userCustomMapHolder[0].trim(), Arrays.copyOfRange(userCustomMapHolder, 1, userCustomMapHolder.length));
			}
			while(userCoreIt.hasNext()) {
				coreKey = userCoreIt.next();
				userCoreMapHolder = config.getStringArray(coreKey);
				ldapUserCore.put(userCoreMapHolder[0].trim(), userCoreMapHolder[1].trim());
			}
			while(groupCoreIt.hasNext()) {
				groupCoreKey = groupCoreIt.next();
				groupCoreMapHolder = config.getStringArray(groupCoreKey);
				ldapGroupCore.put(groupCoreMapHolder[0].trim(), groupCoreMapHolder[1].trim());
			}
		} catch (ConfigurationException | NoSuchElementException e) {
			handleGeneralException(e);
			throw e;
		}
	}

	/**
	 * Methods left from skeleton SDK code. Can't remove or stuff breaks.
	 * None of this is used.
	 */
	public String getUsersFilePath() {
		return usersFilePath;
	}

	public void setUsersFilePath(String usersFilePath) {
		this.usersFilePath = usersFilePath;
	}

	public String getGroupsFilePath() {
		return groupsFilePath;
	}

	public void setGroupsFilePath(String groupsFilePath) {
		this.groupsFilePath = groupsFilePath;
	}
	/**
	 * End Leftovers.
	 *
	 */

	/**
	 * This method creates a user. All the standard attributes of the SCIM User can be retrieved by using the
	 * getters on the SCIMStandardUser member of the SCIMUser object.
	 * <p/>
	 * If there are custom schemas in the SCIMUser input, you can retrieve them by providing the name of the
	 * custom property. (Example : SCIMUser.getStringCustomProperty("schemaName", "customFieldName")), if the
	 * property of string type.
	 * <p/>
	 * This method is invoked when a POST is made to /Users with a SCIM payload representing a user
	 * to be created.
	 * <p/>
	 * NOTE: While the user's group memberships will be populated by Okta, according to the SCIM Spec
	 * (http://www.simplecloud.info/specs/draft-scim-core-schema-01.html#anchor4) that information should be
	 * considered read-only. Group memberships should only be updated through calls to createGroup or updateGroup.
	 *
	 * @param user SCIMUser representation of the SCIM String payload sent by the SCIM client.
	 * @return the created SCIMUser.
	 * @throws OnPremUserManagementException
	 */
	@Override
	public SCIMUser createUser(SCIMUser user) throws OnPremUserManagementException {
		String id = generateNextId(USER_RESOURCE);
		String dnUsername;
		String[] usernameSplit = user.getUserName().split("@");
		user.setId(id);
		LOGGER.info("[createUser] Creating User: " + user.getName().getFormattedName());
		if (userMap == null) {
			//TODO: error code
			throw new OnPremUserManagementException("o01234", "Cannot create the user. The userMap is null", "http://some-help-url", null);
		}
		//TODO: throw in helper
		if(usernameSplit.length != 2) {
			//TODO: error code
			LOGGER.warn("[createUser] Username: " +user.getUserName() + " can only contain one @.");
			throw new OnPremUserManagementException("o01234", "Username can only contain one @.");
		}
		if(useWhitelist && (!usernameWhitelist.contains(usernameSplit[1]))) {
			//TODO: error code
			LOGGER.warn("[createUser] Username: " +user.getUserName() + " is not in the whitelist.");
			throw new OnPremUserManagementException("o01234", "Username domain is not in whitelist.");
		}
		dnUsername = getUserDnName(user.getUserName());
		try {
			LdapContext ctx = new InitialLdapContext(env, null);
			Attributes attrs = constructAttrsFromUser(user, false);
			Name fullName = user.getName();
			String dn = ldapUserPre + dnUsername + "," + ldapUserDn + ldapBaseDn;
			ctx.createSubcontext(dn, attrs);
			ctx.close();
			LOGGER.debug("[createUser] User " + user.getName().getFormattedName() + " successfully inserted into Directory Service.");
		} catch (NamingException | InvalidDataTypeException e) {
			handleGeneralException(e);
			LOGGER.error(e.getMessage());
			//TODO: error code
			throw new OnPremUserManagementException("o01234", e.getMessage(), e);
		}
		return user;
	}

	/**
	 * This method updates a user.
	 * <p/>
	 * This method is invoked when a PUT is made to /Users/{id} with the SCIM payload representing a user to
	 * be updated.
	 * <p/>
	 * NOTE: While the user's group memberships will be populated by Okta, according to the SCIM Spec
	 * (http://www.simplecloud.info/specs/draft-scim-core-schema-01.html#anchor4) that information should be
	 * considered read-only. Group memberships should only be updated through calls to createGroup or updateGroup.
	 *
	 * @param id   the id of the SCIM user.
	 * @param user SCIMUser representation of the SCIM String payload sent by the SCIM client.
	 * @return the updated SCIMUser.
	 * @throws OnPremUserManagementException
	 */
	public SCIMUser updateUser(String id, SCIMUser user) throws OnPremUserManagementException, EntityNotFoundException {
		LOGGER.debug("[updateUser] Updating user: " + user.getName().getFormattedName());
		LOGGER.debug(user.toString());
		String dnUsername, oldDNUsername, oldDN = "";
		String[] usernameSplit = user.getUserName().split("@");
		SCIMUser oldUser;
		//TODO: throw this in a helper
		if(usernameSplit.length != 2) {
			//TODO: error code
			LOGGER.warn("[updateUser] Username: " +user.getUserName() + " can only contain one @.");
			throw new OnPremUserManagementException("o01234", "Username can only contain one @.");
		}
		if(useWhitelist && (!usernameWhitelist.contains(usernameSplit[1]))) {
			//TODO: error code
			LOGGER.warn("[updateUser] Username: " +user.getUserName() + " is not in the whitelist.");
			throw new OnPremUserManagementException("o01234", "Username domain is not in whitelist.");
		}
		try {
			LdapContext ctx = new InitialLdapContext(env, null);
			String searchDN = ldapUserDn + ldapBaseDn;
			dnUsername = getUserDnName(user.getUserName());
			String idLookup = ldapUserCore.get("id");
			String ldapFilter = "(" + idLookup + "=" + id +")";
			ArrayList<Attributes> queryResults = queryLDAP(searchDN, ldapFilter);
			String dn = ldapUserPre + dnUsername + "," + ldapUserDn + ldapBaseDn;
			//TODO refactor this, also make var names consistent
			if(queryResults.size() == 1) {
				oldUser = constructUserFromAttrs(queryResults.get(0));
				oldDNUsername = getUserDnName(oldUser.getUserName());
				oldDN = ldapUserPre + oldDNUsername + "," + ldapUserDn + ldapBaseDn;
				if(user.isActive()) {
					//TODO: detecting uname change and renaming context
					if(!dnUsername.equals(oldDNUsername)) {
						LOGGER.info("[updateUser] User's DN in LDAP has changed from previous value, renaming...");
						ctx.rename(oldDN, dn);
					}
					LOGGER.info("[updateUser] User is still active, modifying user.");
					Attributes attrs = constructAttrsFromUser(user, true);
					String debugKeys = "";
					NamingEnumeration<String> namingEnum = attrs.getIDs();
					while(namingEnum.hasMore()) {
						String key = namingEnum.next();
						debugKeys += key + ", ";
						Attribute attr = attrs.get(key);
						//if(attr.size() > 0) LOGGER.debug(key + " " + attr.get());
						ctx.modifyAttributes(dn, LdapContext.REPLACE_ATTRIBUTE, attrs);
					}
					LOGGER.debug("[updateUser] User " + user.getName().getFormattedName() + " successfully modified in Directory Service with attributes: [" + debugKeys + "]");
					namingEnum.close();
				} else {
					ctx.destroySubcontext(oldDN);
					LOGGER.info("[updateUser] User " + user.getName().getFormattedName() + " successfully deleted from Directory Service.");
				}
			} else {
				//should probably throw an error. TODO
				LOGGER.warn("[updateUser] Connector did not find 1 user with id: " + id + ". Don't know what to do.");
			}
			ctx.close();
		} catch (InvalidDataTypeException | NamingException e) {
			handleGeneralException(e);
			throw new OnPremUserManagementException("o01234", e.getMessage(), e);
		}
		return user;
	}

	/**
	 * Get all the users.
	 * <p/>
	 * This method is invoked when a GET is made to /Users
	 * In order to support pagination (So that the client and the server are not overwhelmed), this method supports querying based on a start index and the
	 * maximum number of results expected by the client. The implementation is responsible for maintaining indices for the SCIM Users.
	 *
	 * @param pageProperties denotes the pagination properties
	 * @param filter         denotes the filter
	 * @return the response from the server, which contains a list of  users along with the total number of results, start index and the items per page
	 * @throws com.okta.scim.server.exception.OnPremUserManagementException
	 *
	 */
	public SCIMUserQueryResponse getUsers(PaginationProperties pageProperties, SCIMFilter filter) throws OnPremUserManagementException {
		List<SCIMUser> users = new ArrayList<SCIMUser>();
		LOGGER.debug("[getUsers(PaginationProperties, SCIMFilter)]");
		try {
			if (filter != null) {
				//Get users based on a filter
				users = getUserByFilter(filter);
				//Example to show how to construct a SCIMUserQueryResponse and how to set stuff.
				SCIMUserQueryResponse response = new SCIMUserQueryResponse();
				//The total results in this case is set to the number of users. But it may be possible that
				//there are more results than what is being returned => totalResults > users.size();
				response.setTotalResults(users.size());
				//Actual results which need to be returned
				response.setScimUsers(users);
				//The input has some page properties => Set the start index.
				if (pageProperties != null) {
					response.setStartIndex(pageProperties.getStartIndex());
				}
				LOGGER.debug("[getUser] Filtered results Returned: " + response.toString());
				return response;
			} else {
				return getUsers(pageProperties);
			}
		} catch (NamingException e) {
			handleGeneralException(e);
			LOGGER.error(e.getMessage());
			//TODO: error code
			throw new OnPremUserManagementException("o01234", e.getMessage(), e);
		}
	}

	private SCIMUserQueryResponse getUsers(PaginationProperties pageProperties) throws NamingException {
		SCIMUserQueryResponse response = new SCIMUserQueryResponse();
		LOGGER.debug("[getUsers(PaginationProperties)] UserMap size: " + userMap.size());
//		if (userMap == null) {
//			//Note that the Error Code "o34567" is arbitrary - You can use any code that you want to.
//			//TODO: error code
//			throw new OnPremUserManagementException("o34567", "Cannot get the users. The userMap is null");
//		}
		ArrayList<Attributes> unprocessedUsers = queryLDAP(ldapUserDn + ldapBaseDn, ldapUserFilter);
		List<SCIMUser> processedUsers = new ArrayList<SCIMUser>();
		for(int i = 0; i < unprocessedUsers.size(); i++) {
			SCIMUser user = constructUserFromAttrs(unprocessedUsers.get(i));
			processedUsers.add(user);
		}
		int totalResults = processedUsers.size();
		if (pageProperties != null) {
			//Set the start index to the response.
			response.setStartIndex(pageProperties.getStartIndex());
		}
		//In this example we are setting the total results to the number of results in this page. If there are more
		//results than the number the client asked for (pageProperties.getCount()), then you need to set the total results correctly
		response.setTotalResults(totalResults);
		//Set the actual results
		response.setScimUsers(processedUsers);
		return response;
	}

	/**
	 * A simple example of how to use <code>SCIMFilter</code> to return a list of users which match the filter criteria.
	 * <p/>
	 * An Admin who configures the UM would specify a SCIM field name as the UniqueId field name. This field and its value would be sent by Okta in the filter.
	 * While implementing the connector, the below points should be noted about the filters.
	 * <p/>
	 * If you choose a single valued attribute as the UserId field name while configuring the App Instance on Okta,
	 * you would get an equality filter here.
	 * For example, if you choose userName, the Filter object below may represent an equality filter like "userName eq "someUserName""
	 * If you choose the name.familyName as the UserId field name, the filter object may represent an equality filter like
	 * "name.familyName eq "someLastName""
	 * If you choose a multivalued attribute (email, for example), the <code>SCIMFilter</code> object below may represent an OR filter consisting of two sub-filters like
	 * "email eq "abc@def.com" OR email eq "def@abc.com""
	 * Of the few multi valued attributes part of the SCIM Core Schema (Like email, address, phone number), only email would be supported as a UserIdField name on Okta.
	 * So, you would have to deal with OR filters only if you choose email.
	 * <p/>
	 * When you get a <code>SCIMFilter</code>, you should check the filter field name (And make sure it is the same field which was configured with Okta), value, condition, etc. as shown in the examples below.
	 *
	 * @param filter the SCIM filter
	 * @return list of users that match the filter
	 */
	private List<SCIMUser> getUserByFilter(SCIMFilter filter) throws NamingException {
		List<SCIMUser> users = new ArrayList<SCIMUser>();

		LOGGER.debug("[getUserByFilter]");
		SCIMFilterType filterType = filter.getFilterType();
		if (filterType.equals(SCIMFilterType.EQUALS)) {
			//Example to show how to deal with an Equality filter
			LOGGER.debug("Equality Filter");
			users = getUsersByEqualityFilter(filter);
		} else if (filterType.equals(SCIMFilterType.OR)) {
			//Example to show how to deal with an OR filter containing multiple sub-filters.
			LOGGER.debug("OR Filter");
			users = getUsersByOrFilter(filter);
		} else {
			LOGGER.error("The Filter " + filter + " contains a condition that is not supported");
		}
		return users;
	}

	/**
	 * This is an example for how to deal with an OR filter. An OR filter consists of multiple sub equality filters.
	 *
	 * @param filter the OR filter with a set of sub filters expressions
	 * @return list of users that match any of the filters
	 */
	private List<SCIMUser> getUsersByOrFilter(SCIMFilter filter) throws NamingException {
		//An OR filter would contain a list of filter expression. Each expression is a SCIMFilter by itself.
		//Ex : "email eq "abc@def.com" OR email eq "def@abc.com""
		List<SCIMFilter> subFilters = filter.getFilterExpressions();
		LOGGER.info("[getUsersByOrFilter] Searching on OR Filter : " + subFilters);
		List<SCIMUser> users = new ArrayList<SCIMUser>();
		LdapContext ctx = new InitialLdapContext(env, null);
		String dn = ldapUserDn + ldapBaseDn;
		ctx.setRequestControls(null);
		SearchControls controls = new SearchControls();
		controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
		String ldapFilter = "";
		String primaryEmailLookup = ldapUserCore.get("primaryEmail");
		String secondaryEmailLookup = ldapUserCore.get("secondaryEmail");
		//Loop through the sub filters to evaluate each of them.
		//Ex : "email eq "abc@def.com""
		for (SCIMFilter subFilter : subFilters) {
			//Name of the sub filter (email)
			String fieldName = subFilter.getFilterAttribute().getAttributeName();
			//Value (abc@def.com)
			String value = subFilter.getFilterValue();
			//For all the users, check if any of them have this email
			//TODO: change the filter to pull values from connector.properties
			if (fieldName.equalsIgnoreCase("email")) {
				ldapFilter = "(|(" + primaryEmailLookup + "=" + value + ")(" + secondaryEmailLookup + "=" + value + "))";
				NamingEnumeration<?> namingEnum = ctx.search(dn, ldapFilter, controls);
				while (namingEnum.hasMore()) {
					SearchResult result = (SearchResult) namingEnum.next();
					Attributes attrs = result.getAttributes();
					SCIMUser user = constructUserFromAttrs(attrs);
					users.add(user);
				}
				ctx.close();
				namingEnum.close();
			}
		}
		LOGGER.info("[getUsersByOrFilter] Users found: " + users.size());
		return users;
	}

	/**
	 * This is an example of how to deal with an equality filter.<p>
	 * If you choose a custom field/complex field (name.familyName) or any other singular field (userName/externalId), you should get an equality filter here.
	 *
	 * @param filter the EQUALS filter
	 * @return list of users that match the filter
	 */
	private List<SCIMUser> getUsersByEqualityFilter(SCIMFilter filter) throws NamingException {
		String fieldName = filter.getFilterAttribute().getAttributeName();
		String value = filter.getFilterValue();
		LdapContext ctx = new InitialLdapContext(env, null);
		String dn = ldapUserDn + ldapBaseDn;
		ctx.setRequestControls(null);
		SearchControls controls = new SearchControls();
		controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
		String ldapFilter = "";
		LOGGER.info("Equality Filter : Field Name [ " + fieldName + " ]. Value [ " + value + " ]");
		List<SCIMUser> users = new ArrayList<SCIMUser>();
		if (fieldName.equalsIgnoreCase("userName")) {
			LOGGER.debug("Username");
			String usernameLookup = ldapUserCore.get("userName");
			if(usernameLookup != null) {
				ldapFilter = "(" + usernameLookup + "=" + value + ")";
			}
			else {
				LOGGER.warn("[getUsersByEqualityFilter] Connector.properties did not have a userName entry for userCoreMap.");
			}
		} else if (fieldName.equalsIgnoreCase("id")) {
			LOGGER.debug("ID");
			String idLookup = ldapUserCore.get("id");
			if(idLookup != null) {
				ldapFilter = "(" + idLookup + "=" + value + ")";
			}
			else {
				LOGGER.warn("[getUsersByEqualityFilter] Connector.properties did not have a id entry for userCoreMap.");
			}
		} else if (fieldName.equalsIgnoreCase("name")) {
			LOGGER.debug("name");
			String subFieldName = filter.getFilterAttribute().getSubAttributeName();
			if (subFieldName != null) {
				if (subFieldName.equalsIgnoreCase("familyName")) {
					//"name.familyName eq "someFamilyName""
					String familyNameLookup = ldapUserCore.get("familyName");
					if(familyNameLookup != null) {
						ldapFilter = "(" + familyNameLookup + "=" + value + ")";
					}
					else {
						LOGGER.warn("[getUsersByEqualityFilter] Connector.properties did not have a familyName entry for userCoreMap.");
					}
				} else if (subFieldName.equalsIgnoreCase("givenName")) {
					//"name.givenName eq "someGivenName""
					String givenNameLookup = ldapUserCore.get("givenName");
					if(givenNameLookup != null) {
						ldapFilter = "(" + givenNameLookup + "=" + value + ")";
					}
					else {
						LOGGER.warn("[getUsersByEqualityFilter] Connector.properties did not have a givenName entry for userCoreMap.");
					}
				}
			}
		} else if (filter.getFilterAttribute().getSchema().equalsIgnoreCase(userCustomUrn)) { //Check that the Schema name is the Custom Schema name to process the filter for custom fields
			LOGGER.debug("Custom");
			String[] keys = ldapUserCustom.keySet().toArray(new String[ldapUserCustom.size()]);
			String[] configLine;
			//"urn:okta:onprem_app:1.0:user:custom:departmentName eq "someValue""
			//Get the custom properties map (SchemaName -> JsonNode)
			for(int i = 0; i < keys.length; i++) {
				configLine = ldapUserCustom.get(keys[i]);
				if(configLine[2].equalsIgnoreCase(fieldName)) {
					ldapFilter = "(" + keys[i] + "=" + value + ")";
					break;
				}
			}
		}
		if(!ldapFilter.isEmpty()) {
			ArrayList<Attributes> queryResults = queryLDAP(dn, ldapFilter);
			for(int i = 0; i < queryResults.size(); i++) {
				SCIMUser user = constructUserFromAttrs(queryResults.get(i));
				users.add(user);
			}
		}
		return users;
	}

	/**
	 * Get a particular user.
	 * <p/>
	 * This method is invoked when a GET is made to /Users/{id}
	 *
	 * @param id the Id of the SCIM User
	 * @return the user corresponding to the id
	 * @throws com.okta.scim.server.exception.OnPremUserManagementException
	 *
	 */
	@Override
	public SCIMUser getUser(String id) throws OnPremUserManagementException, EntityNotFoundException {
		LOGGER.info("[getUser] Id: " + id);
		String searchDN = ldapUserDn + ldapBaseDn;
		String idLookup = ldapUserCore.get("id");
		String ldapFilter = "(" + idLookup + "=" + id +")";
		SCIMUser user;
		try {
			ArrayList<Attributes> queryResults = queryLDAP(searchDN, ldapFilter);
			if(queryResults.size() >= 1) {
				user = constructUserFromAttrs(queryResults.get(0));
				LOGGER.info("[getUser] User found with id: " + id);
				return user;
			} else {
				throw new EntityNotFoundException();
			}
		} catch (NamingException e) {
			handleGeneralException(e);
			throw new OnPremUserManagementException("o01234", e.getMessage(), e);
		}
	}

	/**
	 * This method creates a group. All the standard attributes of the SCIM group can be retrieved by using the
	 * getters on the SCIMStandardGroup member of the SCIMGroup object.
	 * <p/>
	 * If there are custom schemas in the SCIMGroup input, you can retrieve them by providing the name of the
	 * custom property. (Example : SCIMGroup.getCustomProperty("schemaName", "customFieldName"))
	 * <p/>
	 * This method is invoked when a POST is made to /Groups with a SCIM payload representing a group
	 * to be created.
	 *
	 * @param group SCIMGroup representation of the SCIM String payload sent by the SCIM client
	 * @return the created SCIMGroup
	 * @throws com.okta.scim.server.exception.OnPremUserManagementException
	 *
	 */
	@Override
	public SCIMGroup createGroup(SCIMGroup group) throws OnPremUserManagementException, DuplicateGroupException {
		String displayName = group.getDisplayName();
		LOGGER.debug("[createGroup] Creating group: " + group.getDisplayName());
		LOGGER.debug(group.toString());
		boolean duplicate = false;
		String id = generateNextId(GROUP_RESOURCE);
		group.setId(id);
		try {
			LdapContext ctx = new InitialLdapContext(env, null);
			Attributes attrs = constructAttrsFromGroup(group);
			ctx.createSubcontext(ldapGroupPre + group.getDisplayName() + "," + ldapGroupDn + ldapBaseDn, attrs);
			ctx.close();
			LOGGER.info("[createGroup] Group " + group.getDisplayName() + " successfully created.");
		} catch (NamingException e) {
			handleGeneralException(e);
			//TODO: error code
			throw new OnPremUserManagementException("o01234", e.getMessage(), e);
		}
		return group;
	}

	/**
	 * This method updates a group.
	 * <p/>
	 * This method is invoked when a PUT is made to /Groups/{id} with the SCIM payload representing a group to
	 * be updated.
	 *
	 * @param id    the id of the SCIM group
	 * @param group SCIMGroup representation of the SCIM String payload sent by the SCIM client
	 * @return the updated SCIMGroup
	 * @throws com.okta.scim.server.exception.OnPremUserManagementException
	 *
	 */
	public SCIMGroup updateGroup(String id, SCIMGroup group) throws OnPremUserManagementException {
		LOGGER.info("[updateGroup] Updating Group: " + group.getDisplayName());
		LOGGER.debug(group.toString());
		String searchDN = ldapGroupDn + ldapBaseDn;
		String oldDN = "";
		String idLookup = ldapGroupCore.get("id");
		String ldapFilter = "(" + idLookup + "=" + id +")";
		SCIMGroup oldGroup;
		try {
			LdapContext ctx = new InitialLdapContext(env, null);
			ArrayList<Attributes> queryResults = queryLDAP(searchDN, ldapFilter);
			if(queryResults.size() >= 1) {
				oldGroup = constructGroupFromAttrs(queryResults.get(0));
				oldDN = ldapGroupPre + oldGroup.getDisplayName() + "," + ldapGroupDn + ldapBaseDn;
				ctx.destroySubcontext(oldDN);
				LOGGER.info("[updateGroup] Group " + oldGroup.getDisplayName() + " successfully deleted from Directory Service.");
			} else {
				throw new EntityNotFoundException();
			}
			Attributes attrs = constructAttrsFromGroup(group);
			ctx.createSubcontext(ldapGroupPre + group.getDisplayName() + "," + ldapGroupDn + ldapBaseDn, attrs);
			ctx.close();
			LOGGER.info("[updateGroup] Group " + group.getDisplayName() + " successfully re-created.");
			return group;
		} catch (NamingException e) {
			handleGeneralException(e);
			throw new OnPremUserManagementException("o01234", e.getMessage(), e);
		}
	}

	/**
	 * Get all the groups.
	 * <p/>
	 * This method is invoked when a GET is made to /Groups
	 * In order to support pagination (So that the client and the server) are not overwhelmed, this method supports querying based on a start index and the
	 * maximum number of results expected by the client. The implementation is responsible for maintaining indices for the SCIM groups.
	 *
	 * @param pageProperties @see com.okta.scim.util.model.PaginationProperties An object holding the properties needed for pagination - startindex and the count.
	 * @return SCIMGroupQueryResponse the response from the server containing the total number of results, start index and the items per page along with a list of groups
	 * @throws com.okta.scim.server.exception.OnPremUserManagementException
	 *
	 */
	@Override
	public SCIMGroupQueryResponse getGroups(PaginationProperties pageProperties) throws OnPremUserManagementException {
		SCIMGroupQueryResponse response = new SCIMGroupQueryResponse();
		LOGGER.info("[getGroups]");
		try {
			ArrayList<Attributes> unprocessedGroups = queryLDAP(ldapGroupDn + ldapBaseDn, ldapGroupFilter);
			List<SCIMGroup> processedGroups = new ArrayList<SCIMGroup>();
			for(int i = 0; i < unprocessedGroups.size(); i++) {
				SCIMGroup group = constructGroupFromAttrs(unprocessedGroups.get(i));
				processedGroups.add(group);
			}
			int totalResults = processedGroups.size();
			if (pageProperties != null) {
				//Set the start index
				response.setStartIndex(pageProperties.getStartIndex());
			}
			//In this example we are setting the total results to the number of results in this page. If there are more
			//results than the number the client asked for (pageProperties.getCount()), then you need to set the total results correctly
			response.setTotalResults(totalResults);
			//Set the actual results
			response.setScimGroups(processedGroups);
		} catch(NamingException e) {
			handleGeneralException(e);
			LOGGER.error(e.getMessage());
			//TODO: error code
			throw new OnPremUserManagementException("o01234", e.getMessage(), e);
		}
		return response;
	}

	/**
	 * Get a particular group.
	 * <p/>
	 * This method is invoked when a GET is made to /Groups/{id}
	 *
	 * @param id the Id of the SCIM group
	 * @return the group corresponding to the id
	 * @throws com.okta.scim.server.exception.OnPremUserManagementException
	 *
	 */
	public SCIMGroup getGroup(String id) throws OnPremUserManagementException {
		String searchDN = ldapGroupDn + ldapBaseDn;
		String idLookup = ldapGroupCore.get("id");
		String ldapFilter = "(" + idLookup + "=" + id +")";
		SCIMGroup group;
		try{
			ArrayList<Attributes> queryResults = queryLDAP(searchDN, ldapFilter);
			//should never be more than 1 entry
			if(queryResults.size() >= 1) {
				group = constructGroupFromAttrs(queryResults.get(0));
				LOGGER.info("[getGroup] Group found with id: " + id);
			} else {
				throw new EntityNotFoundException();
			}
			return group;
		} catch (NamingException e) {
			handleGeneralException(e);
			throw new OnPremUserManagementException("o01234", e.getMessage(), e);
		}
	}

	/**
	 * Delete a particular group.
	 * <p/>
	 * This method is invoked when a DELETE is made to /Groups/{id}
	 *
	 * @param id the Id of the SCIM group
	 * @throws OnPremUserManagementException
	 */
	public void deleteGroup(String id) throws OnPremUserManagementException, EntityNotFoundException {
		LOGGER.debug("[deleteGroup] Id: " + id);
		String searchDN = ldapGroupDn + ldapBaseDn;
		String idLookup = ldapGroupCore.get("id");
		String ldapFilter = "(" + idLookup + "=" + id +")";
		SCIMGroup oldGroup;
		try{
			ArrayList<Attributes> queryResults = queryLDAP(searchDN, ldapFilter);
			LdapContext ctx = new InitialLdapContext(env, null);
			//should never be more than 1 entry
			if(queryResults.size() >= 1) {
				oldGroup = constructGroupFromAttrs(queryResults.get(0));
				ctx.destroySubcontext(ldapGroupPre + oldGroup.getDisplayName() + "," + ldapGroupDn + ldapBaseDn);
				LOGGER.info("[deleteGroup] Group found with id: " + id);
				ctx.close();
			} else {
				LOGGER.info("[deleteGroup] No Group found with id: " + id + ". I need an adult.");
			}
		} catch (NamingException e) {
			handleGeneralException(e);
			throw new OnPremUserManagementException("o01234", e.getMessage(), e);
		}
	}

	/**
	 * Get all the Okta User Management capabilities that this SCIM Service has implemented.
	 * <p/>
	 * This method is invoked when a GET is made to /ServiceProviderConfigs. It is called only when you are testing
	 * or modifying your connector configuration from the Okta Application instance UM UI. If you change the return values
	 * at a later time please re-test and re-save your connector settings to have your new return values respected.
	 * <p/>
	 * These User Management capabilities help customize the UI features available to your app instance and tells Okta
	 * all the possible commands that can be sent to your connector.
	 *
	 * @return all the implemented User Management capabilities.
	 */
	public UserManagementCapabilities[] getImplementedUserManagementCapabilities() {
		return UserManagementCapabilities.values();
	}

	/**
	 * Generate the next if for a resource
	 *
	 * @param resourceType
	 * @return
	 */
	private String generateNextId(String resourceType) {
		if (useFilePersistence) {
			return UUID.randomUUID().toString();
		}
		if (resourceType.equals(USER_RESOURCE)) {
			return Integer.toString(nextUserId++);
		}
		if (resourceType.equals(GROUP_RESOURCE)) {
			return Integer.toString(nextGroupId++);
		}
		return null;
	}

/********************************************************************
 ******************** Private helpers not in skeleton ***************
 *********************************************************************
 **/
	/**
	 * Constructs Attributes from a SCIMUser object. Only deals with base attributes,
	 * calls constructCustomAttrsFromUser to add custom values to Attributes.
	 * Uses mappings for custom attributes from properties file.
	 *
	 * @param user - SCIMUser object to pull values from
	 * @param update - is this getting called by update or create
	 * @return fully built Attributes Object
	 * @throws InvalidDataTypeException
	 */
	private Attributes constructAttrsFromUser(SCIMUser user, boolean update) throws InvalidDataTypeException {
		String[] keys = ldapUserCore.keySet().toArray(new String[ldapUserCore.size()]);
		String active = user.isActive() ? "active" : "inactive";
		Attributes attrs = new BasicAttributes(true);
		Attribute objclass = new BasicAttribute("objectClass");
		Object value;
		Attribute attr;
		for(int i = 0; i < ldapUserClass.length; i++) objclass.add(ldapUserClass[i]);
		//TODO: fix this, this is ugly
		//For each of the base attribute mappings in properties file, pull the value from user and
		//add it to the attribute object.
		//for any attrs that are not mapped/missing in Okta profile, and are required like uid, want to defer to LDAP exception
		//rather than throwing one here
		for(int i = 0; i < keys.length; i++) {
			String attrType = ldapUserCore.get(keys[i]);
			attr = new BasicAttribute(attrType);
			if(keys[i].equals("userName")) {
				value = user.getUserName();
			} else if(keys[i].equals("familyName")) {
				value = user.getName().getLastName();
			} else if(keys[i].equals("givenName")) {
				value = user.getName().getFirstName();
			} else if(keys[i].equals("formatted")) {
				value = user.getName().getFormattedName();
			} else if(keys[i].equals("id")) {
				value = user.getId();
			} else if(keys[i].equals("password") && (user.getPassword() != null)) {
				attrs.put(attr);
				continue;
			} else if(keys[i].equals("phoneNumbers") && (user.getPhoneNumbers() != null || update)) {
				//if we're doing an update, we always want the attr in place, either it will have a value or if it doesn't
				//then having a no value attr will remove it from the ldap entry
				attrs.put(attr);
				continue;
			} else if(keys[i].equals("emails") && (user.getEmails() != null || update)) {
				attrs.put(attr);
				continue;
			} else {
				continue;
			}
			attr.add(value.toString());
			attrs.put(attr);
		}
		//Special cases for attributes that are not simple values
		//TODO: make this better
		if(user.getPassword() != null && ldapUserCore.get("password") != null) {
			Attribute passwd = attrs.get(ldapUserCore.get("password"));
			passwd.add(user.getPassword());
		}
		user.setPassword("");
		if(user.getPhoneNumbers() != null && ldapUserCore.get("phoneNumbers") != null) {
			Object[] phoneNums = user.getPhoneNumbers().toArray();
			Attribute phoneNumsAttr = attrs.get(ldapUserCore.get("phoneNumbers"));
			for(int i = 0; i < phoneNums.length; i++) {
				PhoneNumber num = (PhoneNumber) phoneNums[i];
				phoneNumsAttr.add(num.getValue());
			}
			attrs.put(phoneNumsAttr);
		}
		if(user.getEmails() != null && ldapUserCore.get("primaryEmail") != null) {
			Attribute primaryEmailAttr = new BasicAttribute(ldapUserCore.get("primaryEmail"));
			Attribute secondaryEmailAttr = new BasicAttribute(ldapUserCore.get("secondaryEmail"));
			Object[] emails = user.getEmails().toArray();
			for(int i = 0; i < emails.length; i++) {
				Email email = (Email) emails[i];//Yo,dawg I hurd you like emails...
				if(email.isPrimary()) {
					primaryEmailAttr.add(email.getValue());
					attrs.put(primaryEmailAttr);
				}
				else if(!email.isPrimary() && ldapUserCore.get("secondaryEmail") != null) {
					secondaryEmailAttr.add(email.getValue());
					attrs.put(secondaryEmailAttr);
				}
			}
		}
		attrs.put(objclass);
		return constructCustomAttrsFromUser(user, attrs, update);
	}

	/**
	 * Adds Attribute objs to supplied attrs made from SCIMUser object.
	 * Uses mappings for custom attributes from properties file.
	 *
	 * @param user - SCIMUser object to pull values from
	 * @param attrs - Attributes to add to SCIMUser object
	 * @param update - 
	 * @return fully built Attributes Object
	 * @throws InvalidDataTypeException
	 */
	private Attributes constructCustomAttrsFromUser(SCIMUser user, Attributes attrs, boolean update) throws InvalidDataTypeException {
		String[] keys = ldapUserCustom.keySet().toArray(new String[ldapUserCustom.size()]);
		String[] configLine;
		String[] emptyArr = new String[0];
		String[] parentNames = emptyArr;
		Attribute customAttr;
		Object value;
		//For each custom attribute mapping in properties, get the appropriate custom value and put it in an Attribute obj
		for(int i = 0; i < keys.length; i++) {
			configLine = ldapUserCustom.get(keys[i]);
			parentNames = emptyArr;
			if(configLine.length > 3) parentNames = Arrays.copyOfRange(configLine, 3, configLine.length);
			if(attrs.get(keys[i]) != null) customAttr = attrs.get(keys[i]);
			else customAttr = new BasicAttribute(keys[i]);
			if(configLine[0].equals("int"))
				value = user.getCustomIntValue(configLine[1], configLine[2], parentNames);
			else if(configLine[0].equals("boolean"))
				value = user.getCustomBooleanValue(configLine[1], configLine[2], parentNames);
			else if(configLine[0].equals("string"))
				value = user.getCustomStringValue(configLine[1], configLine[2], parentNames);
			else if(configLine[0].equals("double"))
				value = user.getCustomDoubleValue(configLine[1], configLine[2], parentNames);
			else
				throw new OnPremUserManagementException("o12345", "Unexpected type for Custom attrs in config: " + Arrays.toString(configLine));
			if(value != null && !value.equals("")) {
				customAttr.add(value.toString());
				attrs.put(customAttr);
			} else if(update) {
				attrs.put(customAttr);
			}
		}
		return attrs;
	}

	/**
	 * Pulls values for base user attributes from Attributes obj and sets it in SCIMUser obj.
	 * Calls constructUserFromCustomAttrs to handle custom attributes.
	 * Mappings obtained from properties file.
	 *
	 * @param attrs - Attributes to add to SCIMUser object
	 * @return fully built SCIMUser object
	 * @throws NamingException
	 */
	private SCIMUser constructUserFromAttrs(Attributes attrs) throws NamingException {
		//create objects, pull in values from attrs using mapping from properties file.
		//TODO: split this into other functions or clean this up
		SCIMUser user = new SCIMUser();
		String formattedNameLookup = ldapUserCore.get("formatted");
		String formattedName = getValueFromAttrs("formatted", formattedNameLookup, attrs);
		String snLookup = ldapUserCore.get("familyName");
		String sn = getValueFromAttrs("familyName", snLookup, attrs);
		String givenNameLookup = ldapUserCore.get("givenName");
		String givenName = getValueFromAttrs("givenName", givenNameLookup, attrs);
		Name fullName = new Name(formattedName, sn, givenName);
		user.setName(fullName);
		String idLookup = ldapUserCore.get("id");
		String id = getValueFromAttrs("id", idLookup, attrs);
		user.setId(id);
		String userNameLookup = ldapUserCore.get("userName");
		String userName = getValueFromAttrs("userName", userNameLookup, attrs);
		user.setUserName(userName);
		ArrayList<PhoneNumber> phoneNums = new ArrayList<PhoneNumber>();
		String phoneNumsAttrLookup = ldapUserCore.get("phoneNumbers");
		Attribute phoneNumsAttr = null;
		if(phoneNumsAttrLookup != null) phoneNumsAttr = attrs.get(phoneNumsAttrLookup);
		else LOGGER.warn("[constructUserFromAttrs] Connector.properties did not have phoneNumbers entry for userCoreMap.");
		//set their password to empty string
		user.setPassword("");
		//for each phone number, parse line from attrs and build PhoneNumber obj
		if(phoneNumsAttr != null) {
			for(int i = 0; i < phoneNumsAttr.size(); i++) {
				String phoneNum = phoneNumsAttr.get(i).toString();
				if(phoneNum != null) {
					PhoneNumber.PhoneNumberType type = PhoneNumber.PhoneNumberType.valueOf("MOBILE");
					PhoneNumber numEntry = new PhoneNumber(phoneNum, type, true);
					phoneNums.add(numEntry);
				}
			}
			user.setPhoneNumbers(phoneNums);
		}
		ArrayList<Email> emails = new ArrayList<Email>();
		String primaryEmailLookup = ldapUserCore.get("primaryEmail");
		String primaryEmail = getValueFromAttrs("primaryEmail", primaryEmailLookup, attrs);;
		Email primaryEmailEntry = new Email(primaryEmail, "primary", true);
		emails.add(primaryEmailEntry);
		String secondaryEmailLookup = ldapUserCore.get("secondaryEmail");
		String secondaryEmail = getValueFromAttrs("secondaryEmail", secondaryEmailLookup, attrs);
		Email secondaryEmailEntry = new Email(secondaryEmail, "secondary", false);
		emails.add(secondaryEmailEntry);
		user.setEmails(emails);
		user.setActive(true);
		return constructUserFromCustomAttrs(user, attrs);
	}

	/**
	 * Adds custom Attributes to given SCIMUser object. Pulls mapping for custom attrs from
	 * properties file.
	 *
	 * @param user - SCIMUser object to add custom attributes to.
	 * @param attrs - Attributes to add to SCIMUser object
	 * @return fully built SCIMUser object
	 * @throws NamingException
	 */
	private SCIMUser constructUserFromCustomAttrs(SCIMUser user, Attributes attrs) throws NamingException {
		String[] keys = ldapUserCustom.keySet().toArray(new String[ldapUserCustom.size()]);
		String[] configLine;
		String[] emptyArr = new String[0];
		String[] parentNames = emptyArr;
		Attribute customAttr;
		Object value = "";
		//Iterates through all mapped custom attrs from properties file and sets value in user obj.
		for(int i = 0; i < keys.length; i++) {
			configLine = ldapUserCustom.get(keys[i]);
			parentNames = emptyArr;
			//LOGGER.debug(Arrays.toString(configLine));
			if(configLine.length > 3) parentNames = Arrays.copyOfRange(configLine, 3, configLine.length);
			customAttr = attrs.get(keys[i]);
			if(customAttr != null) {
				value = customAttr.get();
				//TODO: make this better
				//set type for value pulled from Attributes
				if(configLine[0].equals("int"))
					user.setCustomIntValue(configLine[1], configLine[2], Integer.parseInt(value.toString()), parentNames);
				else if(configLine[0].equals("boolean"))
					user.setCustomBooleanValue(configLine[1], configLine[2], Boolean.valueOf(value.toString()), parentNames);
				else if(configLine[0].equals("string"))
					user.setCustomStringValue(configLine[1], configLine[2], (String) value, parentNames);
				else if(configLine[0].equals("double"))
					user.setCustomDoubleValue(configLine[1], configLine[2], Double.parseDouble(value.toString()), parentNames);
				else
					throw new OnPremUserManagementException("o12345", "Unexpected type for Custom attrs in config: " + Arrays.toString(configLine));
			} else {
				LOGGER.warn("[constructUserFromCustomAttrs] LDAP did not have value for " + keys[i] + ".");
			}
			//TODO: error code
		}
		return user;
	}

	/**
	 * Builds the Attributes object to insert into LDAP, uses mappings pulled from
	 * properties file.
	 *
	 * @param group - SCIMGroup object to build Attributes object from.
	 * @return Attributes object that resulted from SCIMGroup object
	 */
	private Attributes constructAttrsFromGroup(SCIMGroup group) throws NamingException {
		Attributes attrs = new BasicAttributes(true);
		ArrayList<Membership> memberList = new ArrayList<Membership>();
		String[] keys = ldapGroupCore.keySet().toArray(new String[ldapGroupCore.size()]);
		Attribute attr;
		Object value;
		LOGGER.info("[constructAttrsFromGroup] constructing Attrs from group " + group.getDisplayName());
		Attribute objclass = new BasicAttribute("objectClass");
		SCIMFilter filter = new SCIMFilter();
		SCIMFilterAttribute filterAttr = new SCIMFilterAttribute();
		SCIMFilterType filterType = SCIMFilterType.EQUALS;
		filter.setFilterType(filterType);
		filterAttr.setAttributeName("userName");
		filter.setFilterAttribute(filterAttr);
		for(int i = 0; i < ldapGroupClass.length; i++) objclass.add(ldapGroupClass[i]);
		for(int i = 0; i < keys.length; i++) {
			String attrType = ldapGroupCore.get(keys[i]);
			attr = new BasicAttribute(attrType);
			if(keys[i].equals("id")) {
				value = group.getId();
			} else if(keys[i].equals("members") && (group.getMembers() != null)) {
				attrs.put(attr);
				continue;
			} else {
				continue;
			}
			attr.add(value.toString());
			attrs.put(attr);
		}
		Attribute member = attrs.get(ldapGroupCore.get("members"));
		attrs.put(objclass);
		//builds dn from all members, assumes the members are located in the same area as users.
		//TODO: trim down the dups comming from Okta, happens when group push is enabled for a group, assign app to one group, unassign, then assign to another group with same users, their external IDS will be different
		if(group.getMembers() != null && ldapGroupCore.get("members") != null) {
			Object[] members = group.getMembers().toArray();
			for(int i = 0; i < members.length; i++) {
				Membership mem = (Membership) members[i];
				String dnUsername = getUserDnName(mem.getDisplayName());
				filter.setFilterValue(mem.getDisplayName());
				List<SCIMUser> result = getUsersByEqualityFilter(filter);
				String name = ldapUserPre + dnUsername + "," + ldapUserDn + ldapBaseDn;
				DistinguishedName dn = new DistinguishedName(name);
				//check that the member exists in the cache/ldap before making them a member of a group
				if(result.size() == 1) {
					member.add(dn.encode());
					memberList.add(mem);
				}
			}
			//Remove the member attr from the ldap query obj if there are no members to insert
			if(memberList.size() == 0) {
				attrs.remove(ldapGroupCore.get("members"));
			}
		}
		return attrs;
	}

	/**
	 * Helper function that constructs a SCIMGroup object from Attributes
	 * fetched from Ldap. Uses mappings from properties file to set fields in SCIMGroup obj.
	 *
	 * @param attrs - attributes to build SCIMGroup
	 * @return the SCIMGroup object that the attrs created
	 * @throws NamingException
	 */
	private SCIMGroup constructGroupFromAttrs(Attributes attrs) throws NamingException {
		//create objs/get mappings from config file.
		String ldapFilter = "";
		String searchDN = ldapUserDn + ldapBaseDn;
		SCIMGroup group = new SCIMGroup();
		String cn = attrs.get("cn").get().toString();
		LOGGER.debug("[constructGroupFromAttrs] Constructing Group " + cn + " from Attrs.");
		ArrayList<Membership> memberList = new ArrayList<Membership>();
		String memberAttrLookup = ldapGroupCore.get("members");
		Attribute memberAttr = null;
		ArrayList<Attributes> queryResult;
		if(memberAttrLookup != null) memberAttr = attrs.get(memberAttrLookup);
		else LOGGER.warn("[constructGroupFromAttrs] Connector.properties did not have members entry for groupCoreMap.");
		String idLookup = ldapGroupCore.get("id");
		String id = "";
		if(idLookup != null) id = attrs.get(idLookup).get().toString();
		else LOGGER.warn("[constructGroupFromAttrs] Connector.properties did not have id entry for groupCoreMap.");
		group.setDisplayName(cn);
		group.setId(id);
		if(memberAttr != null) {
			for(int i = 0; i < memberAttr.size(); i++) {
				String memberDn = memberAttr.get(i).toString();
				DistinguishedName dn = new DistinguishedName(memberDn);
				LdapRdn memberCn = dn.getLdapRdn(ldapUserPre.split("=")[0]);
				ldapFilter = "(" + ldapUserPre + memberCn.getValue() + ")";
				queryResult = queryLDAP(searchDN ,ldapFilter);
				//should only return one result
				//TODO: check this before using data
				if(queryResult.size() == 1) {
					SCIMUser result = constructUserFromAttrs(queryResult.get(0));
					//searches through cache to retrieve ids for group memebers,used in SCIMGroup
					if(result != null) {
						Membership memHolder = new Membership(result.getId(), result.getUserName());
						memberList.add(memHolder);
					}
				}
			}
			group.setMembers(memberList);
		}
		return group;
	}

	/**
	 * Helper function, uses MessageDigest to hash with SHA, not actually used for us.
	 *
	 * @param password - the password to hash
	 * @return the result of the hash base 64 encoded
	 * @throws NoSuchAlgorithmException
	 * @throws UnsupportedEncodingException
	 */
	private String hashPassword(String password) throws NoSuchAlgorithmException, UnsupportedEncodingException {
		MessageDigest digest = MessageDigest.getInstance("SHA");
		digest.update(password.getBytes("UTF8"));
		byte[] encodedBytes = Base64.encodeBase64(digest.digest());
		String shaPassword = new String(encodedBytes);
		return "{SHA}" + shaPassword;
	}

	/**
	 * Helper function that checks if delimiter exists in string before splitting it.
	 * Probably not super necessary.
	 *
	 * @param s - string to split
	 * @param delim - delimiter to split on
	 * @return result of split operation.
	 */
	private String[] splitString(String s, String delim) throws OnPremUserManagementException{
		if(s.contains(delim)) {
			String[] sParts = s.split(Pattern.quote(delim));//split uses regex, contains uses string literals
			return sParts;
		} else {
			LOGGER.error("[splitString] " + "Cannot parse: " + s + "using delimiter: " + delim);
			//TODO: error code
			throw new OnPremUserManagementException("o2313", "Cannot parse: " + s + "using delimiter: " + delim);
		}
	}

	/**
	 * Helper function to print stack trace to logger.
	 *
	 * @param e - exception to print
	 * @return
	 */
	private void handleGeneralException(Exception e) {
		StringWriter errors = new StringWriter();
		e.printStackTrace(new PrintWriter(errors));
		LOGGER.error(e.getMessage());
		LOGGER.debug(errors.toString());
	}

	/**
	 * Helper function to get appropriate LDAP dn name as per the configs
	 *
	 * @param name - the name to process
	 * @return returns the appropriate dn to use for LDAP
	 */
	private String getUserDnName(String name) {
		if(useEntireUsername) {
			return name;
		}
		else {
			return name.split("@")[0];
		}
	}

	/**
	 * Helper function to get appropriate LDAP dn name as per the configs
	 *
	 * @param name - the name to process
	 * @return returns the appropriate dn to use for LDAP
	 */
	private SCIMUser searchUserUsernames(String name) {
		for(Map.Entry<String, SCIMUser> entry : userMap.entrySet()) {
			SCIMUser user = entry.getValue();
			if(user.getUserName().contains(name)) return user;
		}
		return null;
	}

	private String getValueFromAttrs(String map, String lookup, Attributes attrs) throws NamingException {
		String value = "";
		if(lookup != null) {
			if(attrs.get(lookup) != null) {
				value = attrs.get(lookup).get().toString();
			}
		}
		else {
			LOGGER.warn("[getValueFromAttrs] Connector.properties did not have a " + map + " entry for userCoreMap.");
		}
		return value;
	}

	private ArrayList<Attributes> queryLDAP(String dn, String filter) throws NamingException {
		ArrayList<Attributes> results = new ArrayList<Attributes>();
		LdapContext ctx = new InitialLdapContext(env, null);
		ctx.setRequestControls(null);
		SearchControls controls = new SearchControls();
		controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
		NamingEnumeration<?> namingEnum = ctx.search(dn, filter, controls);
		while (namingEnum.hasMore()) {
			SearchResult result = (SearchResult) namingEnum.next();
			Attributes attrs = result.getAttributes();
			results.add(attrs);
		}
		ctx.close();
		namingEnum.close();
		return results;
	}
}
