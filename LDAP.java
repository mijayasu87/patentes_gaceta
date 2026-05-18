/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ec.gaceta.security;

import java.net.InetAddress;
import java.text.MessageFormat;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
/**
 *
 * @author Michael Yanangómez
 */
public class LDAP {

    public boolean validarIngresoLDAPSinrestrinccion(String user, String pass) {
        validarConexion();
        Hashtable<String, String> env = new Hashtable<String, String>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.PROVIDER_URL, "ldap://192.168.1.1");

        env.put(Context.SECURITY_PRINCIPAL, user + "@iepi");
        env.put(Context.SECURITY_CREDENTIALS, pass);
        DirContext ctx;
        try {
            ctx = new InitialDirContext(env);
            return true;
        } catch (NamingException ex) {
            System.out.println("Error Autenticando mediante LDAP: " + ex.toString());
            return false;
        }
    }
    
    public static final String SEARCH_BY_SAM_ACCOUNT_NAME = "(SAMAccountName={0})";
       public static final String SEARCH_GROUP_BY_GROUP_CN = "(&(objectCategory=group)(cn={0}))";
       public static final String DISTINGUISHED_NAME = "distinguishedName";
       public static final String CN = "cn";
       public static final String MEMBER = "member";
       public static final String MEMBER_OF = "memberOf";

    public int validarIngresoLDAPRestringido(String user, String pass,String grupo) { //SC_Transferencias
        validarConexion();
        Hashtable<String, String> env = new Hashtable<String, String>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.PROVIDER_URL, "ldap://192.168.1.1");

        env.put(Context.SECURITY_PRINCIPAL, user + "@iepi");
        env.put(Context.SECURITY_CREDENTIALS, pass);

        String defaultSearchBase = "OU=Usuarios,OU=Oficina matriz,DC=iepi,DC=gov,DC=EC";
        String groupDistinguishedName = "CN="+grupo+",OU=SistemaCORE,OU=Oficina matriz,DC=iepi,DC=gov,DC=ec"; 
        
        DirContext ctx;
        try {
            ctx = new InitialDirContext(env);

            // userName is SAMAccountName
            SearchResult sr = executeSearchSingleResult(ctx, SearchControls.SUBTREE_SCOPE, defaultSearchBase,
                    MessageFormat.format(SEARCH_BY_SAM_ACCOUNT_NAME, new Object[]{user}),
                    new String[]{DISTINGUISHED_NAME, CN, MEMBER_OF}
            );

            String groupCN = getCN(groupDistinguishedName);
            HashMap processedUserGroups = new HashMap();
            HashMap unProcessedUserGroups = new HashMap();
            // Look for and process memberOf
            if(sr == null){
//                System.out.println("Errorrrrrrrr");
                return -1;
            }
            Attribute memberOf = sr.getAttributes().get(MEMBER_OF);
            if (memberOf != null) {
                for (Enumeration e1 = memberOf.getAll(); e1.hasMoreElements();) {
                    String unprocessedGroupDN = e1.nextElement().toString();
                    String unprocessedGroupCN = getCN(unprocessedGroupDN);
                    
//                    System.out.println("1 "+unprocessedGroupDN);
//                    System.out.println("2 "+unprocessedGroupCN);
                    // Quick check for direct membership
                    if (isSame(groupCN, unprocessedGroupCN) && isSame(groupDistinguishedName, unprocessedGroupDN)) {                        
                        System.out.println(user+ " authorized certificados de descuento");
                        return 1;
                    } else {
                        unProcessedUserGroups.put(unprocessedGroupDN, unprocessedGroupCN);
                    }
                }
//                System.out.println("333333");
                if (userMemberOf(ctx, defaultSearchBase, processedUserGroups, unProcessedUserGroups, groupCN, groupDistinguishedName)) {
                    System.out.println(user+ " authorized certificados de descuento");
                    return 1;
                }
            }

            System.out.println(user+ " not authorized certificados de descuento");

            return -1;
        } catch (Exception ex) {
            System.out.println("Credenciales incorrectas certificados de descuento: " + user);
            return 0;
        }
    }
    
    public static boolean userMemberOf(DirContext ctx, String searchBase, HashMap processedUserGroups, HashMap unProcessedUserGroups, String groupCN, String groupDistinguishedName) throws NamingException {
           HashMap newUnProcessedGroups = new HashMap();
           for (Iterator entry = unProcessedUserGroups.keySet().iterator(); entry.hasNext();) {
               String  unprocessedGroupDistinguishedName = (String) entry.next();
               String unprocessedGroupCN = (String)unProcessedUserGroups.get(unprocessedGroupDistinguishedName);
               if ( processedUserGroups.get(unprocessedGroupDistinguishedName) != null) {
                   //Log.info("Found  : " + unprocessedGroupDistinguishedName +" in processedGroups. skipping further processing of it..." );
                   System.out.println("Found  : " +unprocessedGroupDistinguishedName+ " in processedGroups. skipping further processing of it...");
                   // We already traversed this.
                   continue;
               }
               if (isSame (groupCN, unprocessedGroupCN) && isSame (groupDistinguishedName, unprocessedGroupDistinguishedName)) {
                   System.out.println("Found Match DistinguishedName : " + unprocessedGroupDistinguishedName +", CN : " + unprocessedGroupCN );
                   return true;
               }
           }

           for (Iterator entry = unProcessedUserGroups.keySet().iterator(); entry.hasNext();) {
               String  unprocessedGroupDistinguishedName = (String) entry.next();
               String unprocessedGroupCN = (String)unProcessedUserGroups.get(unprocessedGroupDistinguishedName);

               processedUserGroups.put(unprocessedGroupDistinguishedName, unprocessedGroupCN);

               // Fetch Groups in unprocessedGroupCN and put them in newUnProcessedGroups
               NamingEnumeration ns = executeSearch(ctx, SearchControls.SUBTREE_SCOPE, searchBase,
                       MessageFormat.format( SEARCH_GROUP_BY_GROUP_CN, new Object[] {unprocessedGroupCN}),
                       new String[] {CN, DISTINGUISHED_NAME, MEMBER_OF});

               // Loop through the search results
               while (ns.hasMoreElements()) {
                   SearchResult sr = (SearchResult) ns.next();

                   // Make sure we're looking at correct distinguishedName, because we're querying by CN
                   String userDistinguishedName = sr.getAttributes().get(DISTINGUISHED_NAME).get().toString();
                   if (!isSame(unprocessedGroupDistinguishedName, userDistinguishedName)) {
                       System.out.println("Processing CN : " + unprocessedGroupCN + ", DN : " + unprocessedGroupDistinguishedName +", Got DN : " + userDistinguishedName +", Ignoring...");
                       continue;
                   }

                   System.out.println("Processing for memberOf CN : " + unprocessedGroupCN + ", DN : " + unprocessedGroupDistinguishedName);
                   // Look for and process memberOf
                   Attribute memberOf = sr.getAttributes().get(MEMBER_OF);
                   if (memberOf != null) {
                       for ( Enumeration e1 = memberOf.getAll() ; e1.hasMoreElements() ; ) {
                           String unprocessedChildGroupDN = e1.nextElement().toString();
                           String unprocessedChildGroupCN = getCN(unprocessedChildGroupDN);
                           System.out.println("Adding to List of un-processed groups : " + unprocessedChildGroupDN +", CN : " + unprocessedChildGroupCN);
                           newUnProcessedGroups.put(unprocessedChildGroupDN, unprocessedChildGroupCN);
                       }
                   }
               }
           }
           if (newUnProcessedGroups.size() == 0) {
//               System.out.println("newUnProcessedGroups.size() is 0. returning false...");
               return false;
           }

           //  process unProcessedUserGroups
           return userMemberOf(ctx, searchBase, processedUserGroups, newUnProcessedGroups, groupCN, groupDistinguishedName);
       }
    
    public static boolean isSame(String target, String candidate) {
           if (target != null && target.equalsIgnoreCase(candidate)) {
               return true;
           }
           return false;
       }
    
    public static String getCN(String cnName) {
           if (cnName != null && cnName.toUpperCase().startsWith("CN=")) {
               cnName = cnName.substring(3);
           }
           int position = cnName.indexOf(',');
           if (position == -1) {
               return cnName;
           } else {
               return cnName.substring(0, position);
           }
       }
    
    private static SearchResult executeSearchSingleResult(DirContext ctx, int searchScope,  String searchBase, String searchFilter, String[] attributes) throws NamingException {
           NamingEnumeration result = executeSearch(ctx, searchScope,  searchBase, searchFilter, attributes);

           SearchResult sr = null;
           // Loop through the search results
           while (result.hasMoreElements()) {
               sr = (SearchResult) result.next();
               break;
           }
           return sr;
       }
    
    private static NamingEnumeration executeSearch(DirContext ctx, int searchScope,  String searchBase, String searchFilter, String[] attributes) throws NamingException {
           // Create the search controls
           SearchControls searchCtls = new SearchControls();

           // Specify the attributes to return
           if (attributes != null) {
               searchCtls.setReturningAttributes(attributes);
           }

           // Specify the search scope
           searchCtls.setSearchScope(searchScope);

           // Search for objects using the filter
           NamingEnumeration result = ctx.search(searchBase, searchFilter,searchCtls);
           return result;
       }

    public void validarConexion() {
        try {
            String ipAddress = "192.168.1.1";
            InetAddress inet = InetAddress.getByName(ipAddress);
//            System.out.print("Sending Ping Request to " + ipAddress + " --> ");
//            System.out.println(inet.isReachable(5000) ? "Host is reachable" : "Host is NOT reachable");
        } catch (Exception ex) {
            System.out.println("Error validando conexion ldap certificados de descuento: " + ex);
        }
    }
  
}
