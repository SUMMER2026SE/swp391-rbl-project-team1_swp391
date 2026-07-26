import Link from "next/link";
import { Facebook, Instagram, Twitter, Mail, Phone, MapPin } from "lucide-react";
import { Separator } from "../ui/separator";

export function Footer() {
  return (
    <footer className="bg-card border-t border-border">
      <div className="container mx-auto px-4 py-16">
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-12 lg:gap-8">
          
          {/* Brand */}
          <div className="space-y-6">
            <div>
              <h3 className="text-2xl font-bold mb-2 tracking-tight">SportsBook<span className="text-primary">.</span></h3>
              <p className="text-sm text-muted-foreground leading-relaxed">
                Nền tảng đặt sân thể thao hàng đầu Việt Nam. Khám phá, đặt lịch và kết nối đam mê thể thao của bạn một cách dễ dàng nhất.
              </p>
            </div>
          </div>

          {/* About Us */}
          <div>
            <h4 className="font-semibold mb-6">Về chúng tôi</h4>
            <ul className="space-y-3 text-sm text-muted-foreground">
              <li>
                <Link href="/about" className="hover:text-primary transition-colors flex items-center gap-2 group">
                  <span className="h-1 w-1 rounded-full bg-muted-foreground group-hover:bg-primary transition-colors"></span>
                  Giới thiệu
                </Link>
              </li>
              <li>
                <Link href="/contact" className="hover:text-primary transition-colors flex items-center gap-2 group">
                  <span className="h-1 w-1 rounded-full bg-muted-foreground group-hover:bg-primary transition-colors"></span>
                  Liên hệ
                </Link>
              </li>
              <li>
                <Link href="/privacy" className="hover:text-primary transition-colors flex items-center gap-2 group">
                  <span className="h-1 w-1 rounded-full bg-muted-foreground group-hover:bg-primary transition-colors"></span>
                  Chính sách bảo mật
                </Link>
              </li>
              <li>
                <Link href="/terms" className="hover:text-primary transition-colors flex items-center gap-2 group">
                  <span className="h-1 w-1 rounded-full bg-muted-foreground group-hover:bg-primary transition-colors"></span>
                  Điều khoản sử dụng
                </Link>
              </li>
            </ul>
          </div>

          {/* For Customers */}
          <div>
            <h4 className="font-semibold mb-6">Dành cho khách hàng</h4>
            <ul className="space-y-3 text-sm text-muted-foreground">
              <li>
                <Link href="/search" className="hover:text-primary transition-colors flex items-center gap-2 group">
                  <span className="h-1 w-1 rounded-full bg-muted-foreground group-hover:bg-primary transition-colors"></span>
                  Tìm sân
                </Link>
              </li>
              <li>
                <Link href="/community" className="hover:text-primary transition-colors flex items-center gap-2 group">
                  <span className="h-1 w-1 rounded-full bg-muted-foreground group-hover:bg-primary transition-colors"></span>
                  Cộng đồng thể thao
                </Link>
              </li>
              <li>
                <Link href="/help" className="hover:text-primary transition-colors flex items-center gap-2 group">
                  <span className="h-1 w-1 rounded-full bg-muted-foreground group-hover:bg-primary transition-colors"></span>
                  Trợ giúp
                </Link>
              </li>
            </ul>
          </div>

          {/* Contact & Owner */}
          <div>
            <h4 className="font-semibold mb-6">Liên hệ & Hỗ trợ</h4>
            <ul className="space-y-4 text-sm text-muted-foreground">
              <li className="flex items-start gap-3">
                <MapPin className="h-5 w-5 text-primary shrink-0 mt-0.5" />
                <span>Khu Công Nghệ Cao FPT, Hòa Hải, Đà Nẵng</span>
              </li>
              <li className="flex items-center gap-3">
                <Phone className="h-5 w-5 text-primary shrink-0" />
                <a href="tel:19001234" className="hover:text-primary transition-colors font-medium text-foreground">1900 1234</a>
              </li>
              <li className="flex items-center gap-3">
                <Mail className="h-5 w-5 text-primary shrink-0" />
                <a href="mailto:support@sportsbook.vn" className="hover:text-primary transition-colors">support@sportsbook.vn</a>
              </li>
            </ul>

            <div className="mt-6 pt-6 border-t border-border">
              <h4 className="text-sm font-semibold mb-3">Hợp tác chủ sân</h4>
              <Link 
                href="/register?tab=owner" 
                className="inline-flex items-center justify-center text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring bg-primary text-primary-foreground shadow hover:bg-primary/90 h-9 px-4 py-2 rounded-md w-full"
              >
                Đăng ký cho thuê sân
              </Link>
            </div>
          </div>
        </div>

        <Separator className="my-8" />

        <div className="flex flex-col md:flex-row items-center justify-between gap-4 text-sm text-muted-foreground">
          <div>
            © {new Date().getFullYear()} SportsBook. All rights reserved.
          </div>
          
          {/* Socials & Payments */}
          <div className="flex items-center gap-8">
            <div className="flex items-center gap-4">
              <a href="#" className="hover:text-primary transition-colors" aria-label="Facebook">
                <Facebook className="h-4 w-4" />
              </a>
              <a href="#" className="hover:text-primary transition-colors" aria-label="Instagram">
                <Instagram className="h-4 w-4" />
              </a>
              <a href="#" className="hover:text-primary transition-colors" aria-label="Twitter">
                <Twitter className="h-4 w-4" />
              </a>
            </div>
            
            <div className="hidden sm:flex items-center gap-2">
              <div className="w-10 h-6 bg-muted rounded flex items-center justify-center text-[10px] font-bold text-muted-foreground border border-border">VISA</div>
              <div className="w-10 h-6 bg-muted rounded flex items-center justify-center text-[10px] font-bold text-muted-foreground border border-border">ATM</div>
              <div className="w-10 h-6 bg-muted rounded flex items-center justify-center text-[10px] font-bold text-muted-foreground border border-border">MOMO</div>
            </div>
          </div>
        </div>
      </div>
    </footer>
  );
}

