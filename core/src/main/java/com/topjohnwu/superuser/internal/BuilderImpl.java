/*
 * Copyright 2023 John "topjohnwu" Wu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.topjohnwu.superuser.internal;

import static com.topjohnwu.superuser.Shell.FLAG_MOUNT_MASTER;
import static com.topjohnwu.superuser.Shell.FLAG_NON_ROOT_SHELL;
import static com.topjohnwu.superuser.Shell.FLAG_REDIRECT_STDERR;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

import com.topjohnwu.superuser.NoShellException;
import com.topjohnwu.superuser.Shell;

import java.io.IOException;
import java.lang.reflect.Constructor;

@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class BuilderImpl extends Shell.Builder {
    private static final String TAG = "BUILDER";

    long timeout = 20;
    private int flags = 0;
    private Shell.Initializer[] initializers;
    private String[] command;

    boolean hasFlags(int mask) {
        return (flags & mask) == mask;
    }

    @NonNull
    @Override
    public Shell.Builder setFlags(int f) {
        flags = f;
        return this;
    }

    @NonNull
    @Override
    public Shell.Builder setTimeout(long t) {
        timeout = t;
        return this;
    }

    @NonNull
    @Override
    public Shell.Builder setCommands(String... c) {
        command = c;
        return this;
    }

    public void setInitializersImpl(Class<? extends Shell.Initializer>[] clz) {
        initializers = new Shell.Initializer[clz.length];
        for (int i = 0; i < clz.length; ++i) {
            try {
                Constructor<? extends Shell.Initializer> c = clz[i].getDeclaredConstructor();
                c.setAccessible(true);
                initializers[i] = c.newInstance();
            } catch (ReflectiveOperationException | ClassCastException e) {
                Utils.err(e);
            }
        }
    }

    private ShellImpl start() {
        ShellImpl shell = null;

        // Root mount master
        if (!hasFlags(FLAG_NON_ROOT_SHELL) && hasFlags(FLAG_MOUNT_MASTER)) {
            try {
                shell = exec("suu", "--mount-master");
                if (!shell.isRoot())
                    shell = null;
            } catch (NoShellException ignore) {}
        }

        // Normal root shell
        if (shell == null && !hasFlags(FLAG_NON_ROOT_SHELL)) {
            try {
                shell = exec("su");
                if (!shell.isRoot()) {
                    shell = null;
                }
            } catch (NoShellException ignore) {}
        }

        // Try normal non-root shell
        if (shell == null) {
            if (!hasFlags(FLAG_NON_ROOT_SHELL)) {
                Utils.setConfirmedRootState(false);
            }
            shell = exec("sh");
        }

        return shell;
    }

    private ShellImpl exec(String... commands) {
        try {
            Utils.log(TAG, "exec " + TextUtils.join(" ", commands));
            Process process = Runtime.getRuntime().exec(commands);
            return build(process);
        } catch (IOException e) {
            Utils.ex(e);
            throw new NoShellException("Unable to create a shell!", e);
        }
    }

    @NonNull
    @Override
    public ShellImpl build(Process process) {
        ShellImpl shell;
        try {
            shell = new ShellImpl(this, process);
        } catch (IOException e) {
            Utils.ex(e);
            throw new NoShellException("Unable to create a shell!", e);
        }
        if (hasFlags(FLAG_REDIRECT_STDERR)) {
            Shell.enableLegacyStderrRedirection = true;
        }
        MainShell.setCached(shell);
        if (initializers != null) {
            Context ctx = Utils.getContext();
            for (Shell.Initializer init : initializers) {
                if (init != null && !init.onInit(ctx, shell)) {
                    MainShell.setCached(null);
                    throw new NoShellException("Unable to init shell");
                }
            }
        }
        return shell;
    }

    @NonNull
    @Override
    public ShellImpl build() {
        if (command != null) {
            return exec(command);
        } else {
            return start();
        }
    }
}
