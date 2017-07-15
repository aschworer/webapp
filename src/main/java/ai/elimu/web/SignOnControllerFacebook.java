package ai.elimu.web;

import com.github.scribejava.apis.FacebookApi;
import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.exceptions.OAuthException;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import ai.elimu.dao.ContributorDao;
import ai.elimu.dao.SignOnEventDao;
import ai.elimu.model.Contributor;
import ai.elimu.model.contributor.SignOnEvent;
import org.literacyapp.model.enums.Environment;
import ai.elimu.model.enums.Provider;
import ai.elimu.model.enums.Role;
import ai.elimu.util.ConfigHelper;
import ai.elimu.util.CookieHelper;
import ai.elimu.util.Mailer;
import ai.elimu.util.SlackApiHelper;
import ai.elimu.web.context.EnvironmentContextLoaderListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * See https://developers.facebook.com/apps
 */
@Controller
public class SignOnControllerFacebook {
	
    private OAuth20Service oAuth20Service;

    private Logger logger = Logger.getLogger(getClass());
    
    @Autowired
    private ContributorDao contributorDao;
    
    @Autowired
    private SignOnEventDao signOnEventDao;

    @RequestMapping("/sign-on/facebook")
    public String handleAuthorization(HttpServletRequest request) throws IOException {
        logger.info("handleAuthorization");
		
        String apiKey = "1130171497015759";
        String apiSecret = "d8b49268dacd1e29eca82de8edd88c1c";
        String baseUrl = "http://localhost:8080/webapp";
        if (EnvironmentContextLoaderListener.env == Environment.TEST) {
            apiKey = "1130170237015885";
            apiSecret = ConfigHelper.getProperty("facebook.api.secret");
            baseUrl = "http://" + request.getServerName();
        } else if (EnvironmentContextLoaderListener.env == Environment.PROD) {
            apiKey = "1130160227016886";
            apiSecret = ConfigHelper.getProperty("facebook.api.secret");
            baseUrl = "http://" + request.getServerName();
        }

        oAuth20Service = new ServiceBuilder()
                .apiKey(apiKey)
                .apiSecret(apiSecret)
                .callback(baseUrl + "/sign-on/facebook/callback")
                .scope("email,user_about_me") // https://developers.facebook.com/docs/facebook-login/permissions
                .build(FacebookApi.instance());

        logger.info("Fetching the Authorization URL...");
        String authorizationUrl = oAuth20Service.getAuthorizationUrl();
        logger.info("Got the Authorization URL!");

        return "redirect:" + authorizationUrl;
    }

    @RequestMapping(value="/sign-on/facebook/callback", method=RequestMethod.GET)
    public String handleCallback(HttpServletRequest request, Model model) {
        logger.info("handleCallback");

        if (request.getParameter("param_denied") != null) {
            return "redirect:/sign-on?error=" + request.getParameter("param_denied");
        } else if (request.getParameter("error") != null) {
            return "redirect:/sign-on?error=" + request.getParameter("error");
        } else {
            String code = request.getParameter("code");
            logger.debug("code: " + code);
            
            String responseBody = null;
            try {
                OAuth2AccessToken accessToken = oAuth20Service.getAccessToken(code);
                logger.debug("accessToken: " + accessToken);

                String fields = "?fields=id,email,name,first_name,last_name,gender,link,picture,birthday,age_range,education,work,timezone,hometown,location,friends";
                OAuthRequest oAuthRequest = new OAuthRequest(Verb.GET, "https://graph.facebook.com/v2.8/me" + fields);
                oAuth20Service.signRequest(accessToken, oAuthRequest);
                Response response = oAuth20Service.execute(oAuthRequest);
                responseBody = response.getBody();
                logger.info("response.getCode(): " + response.getCode());
                logger.info("response.getBody(): " + responseBody);
            } catch (InterruptedException | ExecutionException | IOException e) {
                logger.error(null, e);
                return "redirect:/sign-on?login_error=" + e.getMessage();
            }

            Contributor contributor = new Contributor();
            contributor.setReferrer(CookieHelper.getReferrer(request));
            contributor.setUtmSource(CookieHelper.getUtmSource(request));
            contributor.setUtmMedium(CookieHelper.getUtmMedium(request));
            contributor.setUtmCampaign(CookieHelper.getUtmCampaign(request));
            contributor.setUtmTerm(CookieHelper.getUtmTerm(request));
            contributor.setReferralId(CookieHelper.getReferralId(request));
            try {
                JSONObject jsonObject = new JSONObject(responseBody);
                logger.info("jsonObject: " + jsonObject);

                if (jsonObject.has("email")) {
                    // TODO: validate e-mail
                    contributor.setEmail(jsonObject.getString("email"));
                }
                if (jsonObject.has("id")) {
                    contributor.setProviderIdFacebook(jsonObject.getString("id"));
                }
                if (jsonObject.has("picture")) {
                    JSONObject picture = jsonObject.getJSONObject("picture");
                    JSONObject pictureData = picture.getJSONObject("data");
                    contributor.setImageUrl(pictureData.getString("url"));
                }
                if (jsonObject.has("first_name")) {
                    contributor.setFirstName(jsonObject.getString("first_name"));
                }
                if (jsonObject.has("last_name")) {
                    contributor.setLastName(jsonObject.getString("last_name"));
                }
            } catch (JSONException e) {
                logger.error(null, e);
            }

            Contributor existingContributor = contributorDao.read(contributor.getEmail());
            if (existingContributor == null) {
                // Store new Contributor in database
                contributor.setRegistrationTime(Calendar.getInstance());
                if (StringUtils.isNotBlank(contributor.getEmail()) && contributor.getEmail().endsWith("@elimu.ai")) {
                    contributor.setRoles(new HashSet<>(Arrays.asList(Role.ADMIN, Role.ANALYST, Role.CONTRIBUTOR)));
                } else {
                    contributor.setRoles(new HashSet<>(Arrays.asList(Role.CONTRIBUTOR)));
                }
                if (contributor.getEmail() == null) {
                    request.getSession().setAttribute("contributor", contributor);
                    new CustomAuthenticationManager().authenticateUser(contributor);
                    return "redirect:/content/contributor/add-email";
                }
                contributorDao.create(contributor);
                
                // Send welcome e-mail
                String to = contributor.getEmail();
                String from = "elimu.ai <info@elimu.ai>";
                String subject = "Welcome to the community";
                String title = "Welcome!";
                String firstName = StringUtils.isBlank(contributor.getFirstName()) ? "" : contributor.getFirstName();
                String htmlText = "<p>Hi, " + firstName + "</p>";
                htmlText += "<p>Thank you very much for registering as a contributor to the elimu.ai community. We are glad to see you join us!</p>";
                htmlText += "<p>With your help, this is what we aim to achieve:</p>";
                htmlText += "<p><blockquote>\"We build open source tablet software that teaches a child to read, write, and perform arithmetic <i>fully autonomously</i> and without the aid of a teacher. This will help bring literacy to over 57 million children currently out of school.\"</blockquote></p>";
                htmlText += "<p><img src=\"http://elimu.ai/img/banner-en.jpg\" alt=\"\" style=\"width: 564px; max-width: 100%;\" /></p>";
                htmlText += "<h2>Chat</h2>";
                htmlText += "<p>Within the next hour, we will send you an invite to join our Slack channel (to " + contributor.getEmail() + "). There you can chat with the other community members.</p>";
                htmlText += "<h2>Feedback</h2>";
                htmlText += "<p>If you have any questions or suggestions, please contact us by replying to this e-mail or messaging us in Slack.</p>";
                Mailer.sendHtml(to, null, from, subject, title, htmlText);
                
                if (EnvironmentContextLoaderListener.env == Environment.PROD) {
                    // Post notification in Slack
                    String name = "";
                    if (StringUtils.isNotBlank(contributor.getFirstName())) {
                        name += "(";
                        name += contributor.getFirstName();
                        if (StringUtils.isNotBlank(contributor.getLastName())) {
                            name += " " + contributor.getLastName();
                        }
                        name += ")";
                    }
                    String text = URLEncoder.encode("A new contributor " + name + " just joined the community: ") + "http://elimu.ai/content/community/contributors";
                    String iconUrl = contributor.getImageUrl();
                    SlackApiHelper.postMessage(null, text, iconUrl, null);
                }
            } else {
                // Contributor already exists in database
                
                // Update existing contributor with latest values fetched from provider
                if (StringUtils.isNotBlank(contributor.getProviderIdFacebook())) {
                    existingContributor.setProviderIdFacebook(contributor.getProviderIdFacebook());
                }
                if (StringUtils.isNotBlank(contributor.getImageUrl())) {
                    existingContributor.setImageUrl(contributor.getImageUrl());
                }
                // TODO: firstName/lastName
                if (StringUtils.isBlank(existingContributor.getReferrer())) {
                    existingContributor.setReferrer(contributor.getReferrer());
                }
                if (StringUtils.isBlank(existingContributor.getUtmSource())) {
                    existingContributor.setUtmSource(contributor.getUtmSource());
                }
                if (StringUtils.isBlank(existingContributor.getUtmMedium())) {
                    existingContributor.setUtmMedium(contributor.getUtmMedium());
                }
                if (StringUtils.isBlank(existingContributor.getUtmCampaign())) {
                    existingContributor.setUtmCampaign(contributor.getUtmCampaign());
                }
                if (StringUtils.isBlank(existingContributor.getUtmTerm())) {
                    existingContributor.setUtmTerm(contributor.getUtmTerm());
                }
                if (existingContributor.getReferralId() == null) {
                    existingContributor.setReferralId(contributor.getReferralId());
                }
                contributorDao.update(existingContributor);
                
                // Contributor registered previously
                contributor = existingContributor;
            }

            // Authenticate
            new CustomAuthenticationManager().authenticateUser(contributor);

            // Add Contributor object to session
            request.getSession().setAttribute("contributor", contributor);
            
            SignOnEvent signOnEvent = new SignOnEvent();
            signOnEvent.setContributor(contributor);
            signOnEvent.setCalendar(Calendar.getInstance());
            signOnEvent.setServerName(request.getServerName());
            signOnEvent.setProvider(Provider.FACEBOOK);
            signOnEvent.setRemoteAddress(request.getRemoteAddr());
            signOnEvent.setUserAgent(StringUtils.abbreviate(request.getHeader("User-Agent"), 1000));
            signOnEvent.setReferrer(CookieHelper.getReferrer(request));
            signOnEvent.setUtmSource(CookieHelper.getUtmSource(request));
            signOnEvent.setUtmMedium(CookieHelper.getUtmMedium(request));
            signOnEvent.setUtmCampaign(CookieHelper.getUtmCampaign(request));
            signOnEvent.setUtmTerm(CookieHelper.getUtmTerm(request));
            signOnEvent.setReferralId(CookieHelper.getReferralId(request));
            signOnEventDao.create(signOnEvent);

            return "redirect:/content";
        }
    }
}