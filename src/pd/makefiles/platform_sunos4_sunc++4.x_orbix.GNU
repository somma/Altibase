# platform_sunos4_sunc++4.x_orbix.GNU,v 4.6 1997/10/20 15:52:21 levine Exp

# SunOS 4.x (Solaris 1.x) with SunC++ 4.x and Orbix.

debug = 1

CC              = cc
CXX             = CC
CCFLAGS		+= $(CFLAGS)
DCFLAGS		+= -g
CDLD            = $(CC)
DLD             = $(CXX)
LD		= $(CXX)
LIBS            +=
INCLDIRS        = -I$(ORBIX_ROOT)/include
AR              = CC
LDFLAGS         += -L$(ORBIX_ROOT)/lib
ARFLAGS         = -xar -o
PIC             = -pic
RANLIB          = ranlib
SOFLAGS         = -G $(CPPFLAGS)
SOBUILD         = $(COMPILE.cc) $(PIC) -o $(VSHDIR)$*.o $<; \
                  $(SOLINK.cc) -o $@ $(LDFLAGS) $(VSHDIR)$*.o

#### Create template repository to avoid compiler warning.
TEMPLATE_REPOSITORY = Templates.DB
