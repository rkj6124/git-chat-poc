package Utils;

import co.bito.intellij.services.EditorService;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.cef.callback.CefQueryCallback;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BitoUtil {
    public static final EditorService getEditorService(Project project) {
        return (EditorService)getServiceIfNotDisposed(project, EditorService.class);
    }

     public static boolean validateEmailAddress(String email) {
        Pattern pattern;
        Matcher matcher;

        // String EMAIL_PATTERN =  "^[_A-Za-z0-9-\\+]+(\\.[_A-Za-z0-9-]+)*@"
        //         + "[A-Za-z0-9-]+(\\.[A-Za-z0-9]+)*(\\.[A-Za-z]{1,})$";
        String EMAIL_PATTERN = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{1,}$";
        pattern = Pattern.compile(EMAIL_PATTERN);

        matcher = pattern.matcher(email);
        return matcher.matches();
    }
    
    public static final Object getServiceIfNotDisposed(Project project, Class serviceClass) {
        return !project.isDisposed() ? ServiceManager.getService(project, serviceClass) : null;
    }

    public static void copyToClipboard(String text, CefQueryCallback callback) {
        try {
            Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(
                            new StringSelection(text),
                            null
                    );
            System.out.println(" default toolkit is created");
        } catch (Exception e) {
            e.printStackTrace();
            callback.failure(500, e.getMessage());
        }
    }

    public static boolean validateCompanyDomain(String emailDomain, String companyDomain, boolean isAllowJoin) {
        if(isAllowJoin && companyDomain != null && !companyDomain.equals("")) {
            return emailDomain.toLowerCase().endsWith(companyDomain.toLowerCase());
        } else {
            return false;
        }
    }


}
