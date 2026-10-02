package io.aeyer.plowshare.server.information;

/** Identity of a legacy HTTP adapter; payloads and query parameters never provide it. */
public final class InformationCaller {
    private InformationCaller() {}
    public static String account() {
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes request)) return null;
        Object value=request.getRequest().getAttribute(io.aeyer.plowshare.server.auth.AuthFilter.HANDLE_ATTRIBUTE);
        return value instanceof String handle?handle:null;
    }
}
