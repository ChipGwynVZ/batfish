package org.batfish.representation.host;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.batfish.common.VendorConversionException;
import org.batfish.common.util.BatfishObjectMapper;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.ConfigurationFormat;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.StaticRoute;
import org.batfish.datamodel.collections.RoleSet;
import org.batfish.main.Warnings;
import org.batfish.representation.VendorConfiguration;
import org.batfish.representation.iptables.IptablesVendorConfiguration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class HostConfiguration implements VendorConfiguration {

   private static final String HOST_INTERFACES_VAR = "hostInterfaces";

   private static final String HOSTNAME_VAR = "hostname";

   private static final String IPTABLES_FILE_VAR = "iptablesFile";

   /**
    *
    */
   private static final long serialVersionUID = 1L;

   public static HostConfiguration fromJson(String text, Warnings warnings)
         throws JsonParseException, JsonMappingException, IOException {
      ObjectMapper mapper = new BatfishObjectMapper();
      HostConfiguration hostConfiguration = mapper.readValue(text,
            HostConfiguration.class);
      hostConfiguration._warnings = warnings;
      return hostConfiguration;
   }

   private Configuration _c;

   protected final Map<String, HostInterface> _hostInterfaces;

   private String _hostname;

   private String _iptablesFile;

   private IptablesVendorConfiguration _iptablesVendorConfig;

   protected final RoleSet _roles = new RoleSet();

   private final Set<HostStaticRoute> _staticRoutes;

   // @JsonCreator
   // public HostConfiguration(@JsonProperty(HOSTNAME_VAR) String name) {
   // _hostname = name;
   // _interfaces = new HashMap<String, Interface>();
   // _roles = new RoleSet();
   // }

   private transient Set<String> _unimplementedFeatures;

   private transient Warnings _warnings;

   public HostConfiguration() {
      _hostInterfaces = new TreeMap<String, HostInterface>();
      _staticRoutes = new TreeSet<HostStaticRoute>();
   }

   @JsonProperty(HOST_INTERFACES_VAR)
   public Map<String, HostInterface> getHostInterfaces() {
      return _hostInterfaces;
   }

   @JsonProperty(HOSTNAME_VAR)
   @Override
   public String getHostname() {
      return _hostname;
   }

   public Map<String, Interface> getInterfaces() {
      throw new UnsupportedOperationException(
            "no implementation for generated method");
   }

   @JsonProperty(IPTABLES_FILE_VAR)
   public String getIptablesFile() {
      return _iptablesFile;
   }

   @JsonIgnore
   @Override
   public RoleSet getRoles() {
      return _roles;
   }

   @JsonIgnore
   @Override
   public Set<String> getUnimplementedFeatures() {
      return _unimplementedFeatures;
   }

   @JsonIgnore
   @Override
   public Warnings getWarnings() {
      return _warnings;
   }

   @Override
   public void setHostname(String hostname) {
      _hostname = hostname;
   }

   public void setIptablesConfig(IptablesVendorConfiguration config) {
      _iptablesVendorConfig = config;
   }

   public void setIptablesFile(String file) {
      _iptablesFile = file;
   }

   @Override
   public void setRoles(RoleSet roles) {
      _roles.addAll(roles);
   }

   @JsonIgnore
   @Override
   public void setVendor(ConfigurationFormat format) {
      throw new UnsupportedOperationException(
            "Cannot set vendor for host configuration");
   }

   @Override
   public Configuration toVendorIndependentConfiguration(Warnings warnings)
         throws VendorConversionException {
      _warnings = warnings;
      String hostname = getHostname();
      _c = new Configuration(hostname);
      _c.setConfigurationFormat(ConfigurationFormat.HOST);
      _c.setRoles(_roles);

      // add interfaces
      for (HostInterface hostInterface : _hostInterfaces.values()) {
         _c.getInterfaces().put(hostInterface.getName(),
               hostInterface.toInterface(_c, warnings));
      }

      // add iptables
      if (_iptablesVendorConfig != null) {
         _iptablesVendorConfig.addAsIpAccessLists(_c, warnings);
      }

      if (_staticRoutes.isEmpty()) {
         for (String ifaceName : _c.getInterfaces().keySet()) {
            StaticRoute sr = new StaticRoute(Prefix.ZERO, null, ifaceName,
                  StaticRoute.NO_TAG);
            sr.setAdministrativeCost(HostStaticRoute.DEFAULT_ADMINISTRATIVE_COST);
            _c.getStaticRoutes().add(sr);
         }
      }
      else {
         _c.getStaticRoutes().addAll(
               _staticRoutes.stream().map(hsr -> hsr.toStaticRoute())
                     .collect(Collectors.toSet()));
      }
      return _c;
   }
}
