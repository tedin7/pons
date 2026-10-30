/*
 * pons-input: turns simple text commands on stdin into X11 input events (XTEST).
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
#include <time.h>

/* Games that poll the keyboard once per frame (CK3 at 30 fps) never see a key that
 * goes down and up within one frame: a press is held at least this long. */
#define MIN_HOLD_NS 80000000L

static long long now_ns(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000000000LL + t.tv_nsec;
}

/* When each keycode last went down, to stretch too-short presses. */
static long long pressed_at[256];

static void key_event(Display *display, KeyCode code, int down)
{
    if (down) {
        pressed_at[code] = now_ns();
    } else if (pressed_at[code]) {
        long long left = MIN_HOLD_NS - (now_ns() - pressed_at[code]);
        if (left > 0) {
            XFlush(display);
            struct timespec wait = { 0, (long)left };
            nanosleep(&wait, NULL);
        }
        pressed_at[code] = 0;
    }
    XTestFakeKeyEvent(display, code, down, CurrentTime);
}

/* Presses and releases the key for sym, holding Shift if the symbol needs it. */
static void type_keysym(Display *display, KeySym sym)
{
    KeyCode code = XKeysymToKeycode(display, sym);
    if (!code) return;
    int shifted = XkbKeycodeToKeysym(display, code, 0, 0) != sym &&
                  XkbKeycodeToKeysym(display, code, 0, 1) == sym;
    KeyCode shift = XKeysymToKeycode(display, XK_Shift_L);
    if (shifted && shift) key_event(display, shift, True);
    key_event(display, code, True);
    key_event(display, code, False);
    if (shifted && shift) key_event(display, shift, False);
}

int main(void) {
    Display *display = XOpenDisplay(NULL);
    if (!display) {
        fprintf(stderr, "pons-input: cannot open display\n");
        return 1;
    }
    int ev, err, major, minor;
    if (!XTestQueryExtension(display, &ev, &err, &major, &minor)) {
        fprintf(stderr, "pons-input: XTEST missing\n");
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
                if (code) key_event(display, code, b != 0);
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
