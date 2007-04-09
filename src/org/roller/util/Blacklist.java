/*
 * Created on Nov 11, 2003
 */
package org.roller.util;

import org.roller.util.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Based on the list provided by Jay Allen for
 * MT-Blacklist:
 * http://www.jayallen.org/projects/mt-blacklist/
 * 
 * Will provide response whether submitted string
 * contains an item listed in the supplied blacklist.
 * This implementation does not do everything
 * MT-Blacklist does, such as the "Search &amp; De-spam mode".
 * 
 * @author lance
 */
public class Blacklist
{
    private static Log mLogger = LogFactory.getLog(Blacklist.class);

    private static Blacklist blacklist;
    
    public  static final String blacklistFile = "blacklist.txt";
    private static final String blacklistURL = "http://www.jayallen.org/comment_spam/blacklist.txt";
    private static final String lastUpdateStr = "Last update:";

    // Default location of blacklist file (relative to realPath) in case that uploadDir is null or empty
    // and realPath is non-null.
    private static final String DEFAULT_BLACKLIST_DIR = "resources";
    private String realPath;
    private String uploadDir;

    private List blacklistStr = new LinkedList();
    private List blacklistRegex = new LinkedList();
    
    private Date ifModifiedSince = null;

    /**
     * Singleton factory method.
     */
    public static Blacklist getBlacklist(String realPath, String uploadDir)
    {
        if (blacklist == null)
        {
            Blacklist temp = new Blacklist(realPath, uploadDir);
            temp.extractFromFile();
            blacklist = temp;
        }
        return blacklist;
    }
    
    /**
     * This will try to download a new set of Blacklist
     * rules.  If no change has occurred then return
     * current Blacklist.
     * 
     * @return New Blacklist if rules have changed,
     * otherwise return current Blacklist.
     */
    public static void checkForUpdate()
    {
        blacklist = blacklist.extractFromURL();
    }

    /**
     * Hide constructor
     */
    private Blacklist(String realPath, String uploadDir)
    {
        this.realPath = realPath;
        this.uploadDir = uploadDir;
    }
    
    /**
     * Read a local file for Blacklist rules.
     */
    private void extractFromFile()
    {
        InputStream txtStream = getFileInputStream();
        if (txtStream != null)
        {
            readFromStream(txtStream, false);
        }
        else
        {
            throw new NullPointerException("Unable to load blacklist.txt.  " +
            "Make sure blacklist.txt is in classpath.");
        }    
    }

    /**
     * Read in the InputStream for rules.
     * @param txtStream
     */
    private String readFromStream(InputStream txtStream, boolean saveStream)
    {
        String line;
        StringBuffer buf = new StringBuffer();
        BufferedReader in = null;
        try
        {
            in = new BufferedReader( 
                new InputStreamReader( txtStream, "UTF-8" ) );
            while ((line = in.readLine()) != null)
            {
                if (line.startsWith("#"))
                {
                    readComment(line);
                }
                else
                {
                    readRule(line);
                }
                
                if (saveStream) buf.append(line).append("\n");
            }
        }
        catch (Exception e)
        {
            mLogger.error(e);
        }
        finally
        {
           try
            {
                 if (in != null) in.close();
            }
            catch (IOException e1)
            {
                mLogger.error(e1);
            }
        }
        return buf.toString();
    }
    
    /**
     * Connect to the web for blacklist.  Check to
     * see if a newer version exists before parsing.
     */
    private Blacklist extractFromURL()
    {
        // now see if we can update it from the web
        Blacklist oldBlacklist = getBlacklist(realPath, uploadDir);
        Blacklist newBlacklist = new Blacklist(realPath, uploadDir);
        try
        {
            URL url = new URL(blacklistURL);
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            if (oldBlacklist.ifModifiedSince != null)
            {
                connection.setRequestProperty("If-Modified-Since",
                                              DateUtil.formatRfc822(oldBlacklist.ifModifiedSince));
            }

            // did the connection return NotModified? If so, no need to parse
            if ( connection.getResponseCode() == HttpURLConnection.HTTP_NOT_MODIFIED)
            {
                // we already have a current blacklist
                return oldBlacklist;
            }

            // did the connection return a LastModified header?
            long lastModifiedLong = connection.getHeaderFieldDate("Last-Modified", -1);

            // if no ifModifiedSince, or lastModifiedLong is newer, then read stream
            if (oldBlacklist.ifModifiedSince == null ||
                oldBlacklist.ifModifiedSince.getTime() < lastModifiedLong)
            {
                String results = newBlacklist.readFromStream( connection.getInputStream(), true );

                // save the new blacklist
                newBlacklist.writeToFile(results);

                if (newBlacklist.ifModifiedSince == null && lastModifiedLong != -1)
                {
                    newBlacklist.ifModifiedSince = new Date(lastModifiedLong);
                }

                return newBlacklist;
            }
        }
        catch (Exception e)
        {
            // Catch all exceptions and just log at INFO (should this be WARN?) without a full stacktrace.
            mLogger.info("Roller Blacklist Update: Unable to update comment spam blacklist due to exception: " + e);
        }
        return oldBlacklist;
    }

    /**
     * @param str
     */
    private void readRule(String str)
    {
        if (StringUtils.isEmpty(str)) return; // bad condition
        
        String rule = str.trim();
        
        if (str.indexOf("#") > 0) // line has a comment
        {
            int commentLoc = str.indexOf("#");
            rule = str.substring(0, commentLoc-1).trim(); // strip comment
        }
        
        if (rule.indexOf( "(" ) > -1) // regex rule
        {
            // pre-compile patterns since they will be frequently used
            blacklistRegex.add(Pattern.compile(rule));
        }
        else if (StringUtils.isNotEmpty(rule))
        {    
            blacklistStr.add(rule);
        }
    }

    /**
     * Try to parse out "Last update" value: 2004/03/08 23:17:30.
     * @param str
     */
    private void readComment(String str)
    {
        int lastUpdatePos = str.indexOf(lastUpdateStr);
        if (lastUpdatePos > -1)
        {
            str = str.substring(lastUpdatePos + lastUpdateStr.length());
            str = str.trim();
            try
            {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
                ifModifiedSince = DateUtil.parse(str, sdf);
            }
            catch (ParseException e)
            {
                mLogger.debug("ParseException reading " + str);
            }
        }
    }

    /**
     * Does the String argument match any of the rules in the blacklist?
     * 
     * @param str
     * @return
     */
    public boolean isBlacklisted(String str)
    {
        if (str == null || StringUtils.isEmpty(str)) return false;
        
        // First iterate over blacklist, doing indexOf.
        // Then iterate over blacklistRegex and test.
        // As soon as there is a hit in either case return true 
        
        // test plain String.indexOf
        if( testStringRules(str) ) return true;
        
        // test regex blacklisted
        return testRegExRules(str);
    }
    
    /**
     * Test String against the RegularExpression rules.
     * 
     * @param str
     * @return
     */
    private boolean testRegExRules(String str)
    {
        boolean hit = false;
        Pattern testPattern = null;
        Iterator iter = blacklistRegex.iterator();
        while (iter.hasNext())
        {
            testPattern = (Pattern)iter.next();
            
            // want to see what it is matching on
            // if we are in "debug mode"
            if (mLogger.isDebugEnabled())
            {
                Matcher matcher = testPattern.matcher(str);
                if (matcher.find())
                {
                    mLogger.debug(matcher.group() + " matched by " + testPattern.pattern());
                    hit = true;
                    break;
                }
            }
            else
            {
                if (testPattern.matcher(str).find())
                {
                    hit = true;
                    break;
                }
            }
        }
        return hit;
    }

    /**
     * Test the String against the String rules,
     * using simple indexOf.
     * 
     * @param str
     * @return
     */
    private boolean testStringRules(String str)
    {
        String test;
        Iterator iter = blacklistStr.iterator();
        boolean hit = false;
        while (iter.hasNext())
        {
            test = (String)iter.next();
            //System.out.println("check against |" + test + "|");
            if (str.indexOf(test) > -1)
            {
                // want to see what it is matching on
                if (mLogger.isDebugEnabled())
                {
                    mLogger.debug("matched:" + test + ":");
                }
                hit = true;
                break;
            }
        }
        return hit;
    }
    
    /**
     * Try reading blacklist.txt from wherever RollerConfig.getUploadDir()
     * is, otherwise try loading it from web resource (/WEB-INF/).
     */
    private InputStream getFileInputStream()
    {
        try
        {
            // TODO: clean up
            // This was previously throwing an NPE to get to the exception case 
            // when being called in several places with indexDir==null. 
            // This is just about as bad; it needs to be cleaned up.
            String path = getBlacklistFilePath();
            if (path == null)
            {
                throw new FileNotFoundException(
                        "null path (indexDir and realPath both null)");
            }
            return new FileInputStream( path );
        }
        catch (Exception e)
        {
            return getClass().getResourceAsStream("/"+blacklistFile);
        }
    }

    /**
     * @param results
     */
    private void writeToFile(String results)
    {
        FileWriter out = null;
        String path = getBlacklistFilePath();
        if (path == null)
        {
            mLogger.debug("Not writing blacklist file since directory paths were null.");
            return;
        }
        try
        {
            // attempt writing results
            out = new FileWriter(path);
            out.write( results.toCharArray() );
        }
        catch (Exception e)
        {
            mLogger.info("Unable to write new " + path);
        }
        finally
        {
            try
            {
                if (out != null) out.close();
            }
            catch (IOException e)
            {
                mLogger.error("Unable to close stream to " + path);
            }
        }
    }

    // Added for ROL-612 - TODO: Consider refactoring - nearly duplicate code in FileManagerImpl.
    private String getBlacklistFilePath() 
    {
        if (uploadDir == null && realPath==null) 
        {
            // to preserve existing behavior forced to interpret this differently
            return null;
        }
        if (uploadDir == null || uploadDir.trim().length() == 0) 
        {
            uploadDir = realPath + File.separator + DEFAULT_BLACKLIST_DIR;
        }
        return uploadDir + File.separator + blacklistFile;
    }

    /**
     * Return pretty list of String and RegEx rules.
     */
    public String toString()
    {
        StringBuffer buf = new StringBuffer("blacklist ");
        buf.append(blacklistStr).append("\n");
        buf.append("Regex blacklist ").append(blacklistRegex);
        return buf.toString();
    }
}