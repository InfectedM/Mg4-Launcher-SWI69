package com.mg4.launcher.svc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.mg4.launcher.ui.Prefs;

/**
 * Sets the GitHub token from a computer, without typing it on the car screen:
 * adb shell am broadcast -n com.mg4.launcher/.svc.TokenReceiver --es token ghp_xxx [--es repo user/repo]
 * Protected by DUMP: only adb (shell) and the system can send it.
 */
public final class TokenReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Prefs p = new Prefs(c);
        String tok = i.getStringExtra("token");
        String repo = i.getStringExtra("repo");
        if (tok != null) p.setGithubToken(tok);
        if (repo != null) p.setUpdateRepo(repo);
        Log.i("MG4L", "GitHub token " + (tok == null ? "unchanged" : tok.isEmpty() ? "removed" : "saved") + ", repo " + p.updateRepo());
        setResultData("ok: repo " + p.updateRepo());
    }
}
