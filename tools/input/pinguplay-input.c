/*
 * pinguplay-input: turns simple text commands on stdin into X11 input events (XTEST).
 *
 *   M <x> <y>        move the pointer to absolute screen coordinates
 *   B <button> <0|1> release/press a mouse button (4/5 = wheel up/down)
 *   K <keysym> <0|1> release/press a key, keysym in hex (e.g. ff0d = Return)
 *   T <keysym>       type one character: press and release, adding Shift when the
 *                    keysym sits on the shifted level of its key (e.g. 'A', '!')
 *
 * One command per line. Unknown or malformed lines are ignored.
 */
#include <X11/Xlib.h>
#include <X11/XKBlib.h>
#include <X11/extensions/XTest.h>
#include <X11/keysym.h>
#include <stdio.h>
#include <stdlib.h>

/* Presses and releases the key for sym, holding Shift if the symbol needs it. */
static void type_keysym(Display *display, KeySym sym)
{
    KeyCode code = XKeysymToKeycode(display, sym);
    if (!code) return;
    int shifted = XkbKeycodeToKeysym(display, code, 0, 0) != sym &&
                  XkbKeycodeToKeysym(display, code, 0, 1) == sym;
    KeyCode shift = XKeysymToKeycode(display, XK_Shift_L);
    if (shifted && shift) XTestFakeKeyEvent(display, shift, True, CurrentTime);
    XTestFakeKeyEvent(display, code, True, CurrentTime);
    XTestFakeKeyEvent(display, code, False, CurrentTime);
    if (shifted && shift) XTestFakeKeyEvent(display, shift, False, CurrentTime);
}

int main(void) {
    Display *display = XOpenDisplay(NULL);
    if (!display) {
        fprintf(stderr, "pinguplay-input: cannot open display\n");
        return 1;
    }
    int ev, err, major, minor;
    if (!XTestQueryExtension(display, &ev, &err, &major, &minor)) {
        fprintf(stderr, "pinguplay-input: XTEST missing\n");
        return 1;
    }
    setvbuf(stdin, NULL, _IOLBF, 0);

    char line[128];
    while (fgets(line, sizeof line, stdin)) {
        int a, b;
        unsigned long sym;
        switch (line[0]) {
        case 'M':
            if (sscanf(line + 1, "%d %d", &a, &b) == 2)
                XTestFakeMotionEvent(display, -1, a, b, CurrentTime);
            break;
        case 'B':
            if (sscanf(line + 1, "%d %d", &a, &b) == 2 && a >= 1 && a <= 9)
                XTestFakeButtonEvent(display, (unsigned)a, b != 0, CurrentTime);
            break;
        case 'T':
            if (sscanf(line + 1, "%lx", &sym) == 1) type_keysym(display, (KeySym)sym);
            break;
        case 'K':
            if (sscanf(line + 1, "%lx %d", &sym, &b) == 2) {
                KeyCode code = XKeysymToKeycode(display, (KeySym)sym);
                if (code) XTestFakeKeyEvent(display, code, b != 0, CurrentTime);
            }
            break;
        default:
            break;
        }
        XFlush(display);
    }
    XCloseDisplay(display);
    return 0;
}
