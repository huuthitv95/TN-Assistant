package main

import (
	"net/http"
	"net/url"
)

// NewsSource records user-selected publisher homepages. It intentionally stores
// links and attribution only; articles and audio remain with their publishers
// unless TN has separately confirmed rights for a managed-news item.
type NewsSource struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	URL      string `json:"url"`
	Region   string `json:"region"`
	Category string `json:"category"`
	Language string `json:"language"`
}

var publisherNewsSources = []NewsSource{
	{"vnexpress", "VnExpress", "https://vnexpress.net", "Việt Nam", "Thời sự, Chính trị & Xã hội Tổng hợp", "vi"},
	{"tuoi-tre", "Báo Tuổi Trẻ", "https://tuoitre.vn", "Việt Nam", "Thời sự, Chính trị & Xã hội Tổng hợp", "vi"},
	{"thanh-nien", "Báo Thanh Niên", "https://thanhnien.vn", "Việt Nam", "Thời sự, Chính trị & Xã hội Tổng hợp", "vi"},
	{"dan-tri", "Báo Dân Trí", "https://dantri.com.vn", "Việt Nam", "Thời sự, Chính trị & Xã hội Tổng hợp", "vi"},
	{"vietnamnet", "Báo VietNamNet", "https://vietnamnet.vn", "Việt Nam", "Thời sự, Chính trị & Xã hội Tổng hợp", "vi"},
	{"nhan-dan", "Báo Nhân Dân", "https://nhandan.vn", "Việt Nam", "Thời sự, Chính trị & Xã hội Tổng hợp", "vi"},
	{"lao-dong", "Báo Lao Động", "https://laodong.vn", "Việt Nam", "Thời sự, Chính trị & Xã hội Tổng hợp", "vi"},
	{"tien-phong", "Báo Tiền Phong", "https://tienphong.vn", "Việt Nam", "Thời sự, Chính trị & Xã hội Tổng hợp", "vi"},
	{"bao-chinh-phu", "Báo Chính phủ", "https://baochinhphu.vn", "Việt Nam", "Thời sự, Chính trị & Xã hội Tổng hợp", "vi"},
	{"bao-tin-tuc", "Thông tấn xã Việt Nam (VNA)", "https://baotintuc.vn", "Việt Nam", "Thời sự, Chính trị & Xã hội Tổng hợp", "vi"},
	{"vtv", "Báo điện tử VTV", "https://vtv.vn", "Việt Nam", "Đài Truyền hình & Phát thanh Quốc gia", "vi"},
	{"vov", "Đài Tiếng nói Việt Nam (VOV)", "https://vov.vn", "Việt Nam", "Đài Truyền hình & Phát thanh Quốc gia", "vi"},
	{"quochoi-tv", "Truyền hình Quốc hội Việt Nam", "https://quochoitv.vn", "Việt Nam", "Đài Truyền hình & Phát thanh Quốc gia", "vi"},
	{"saigon-times", "Thời báo Kinh tế Sài Gòn", "https://thesaigontimes.vn", "Việt Nam", "Kinh tế, Tài chính & Đầu tư", "vi"},
	{"bao-dau-tu", "Báo Đầu tư", "https://baodautu.vn", "Việt Nam", "Kinh tế, Tài chính & Đầu tư", "vi"},
	{"vneconomy", "Tạp chí Kinh tế Việt Nam (VnEconomy)", "https://vneconomy.vn", "Việt Nam", "Kinh tế, Tài chính & Đầu tư", "vi"},
	{"cafef", "CafeF", "https://cafef.vn", "Việt Nam", "Kinh tế, Tài chính & Đầu tư", "vi"},
	{"vietnambiz", "Vietnambiz", "https://vietnambiz.vn", "Việt Nam", "Kinh tế, Tài chính & Đầu tư", "vi"},
	{"tinhte", "Tinhte", "https://tinhte.vn", "Việt Nam", "Công nghệ, Khoa học & Đời sống", "vi"},
	{"khoa-hoc-phat-trien", "Báo Khoa học và Phát triển", "https://khoahocphattrien.vn", "Việt Nam", "Công nghệ, Khoa học & Đời sống", "vi"},
	{"kenh14", "Kênh 14", "https://kenh14.vn", "Việt Nam", "Công nghệ, Khoa học & Đời sống", "vi"},
	{"reuters", "Reuters", "https://reuters.com", "Quốc tế", "Tin tức Toàn cầu, Chính trị & Tổng hợp", "en"},
	{"ap-news", "Associated Press (AP News)", "https://apnews.com", "Quốc tế", "Tin tức Toàn cầu, Chính trị & Tổng hợp", "en"},
	{"bbc-news", "BBC News", "https://bbc.com", "Quốc tế", "Tin tức Toàn cầu, Chính trị & Tổng hợp", "en"},
	{"new-york-times", "The New York Times", "https://nytimes.com", "Quốc tế", "Tin tức Toàn cầu, Chính trị & Tổng hợp", "en"},
	{"the-guardian", "The Guardian", "https://theguardian.com", "Quốc tế", "Tin tức Toàn cầu, Chính trị & Tổng hợp", "en"},
	{"cnn", "CNN International", "https://cnn.com", "Quốc tế", "Tin tức Toàn cầu, Chính trị & Tổng hợp", "en"},
	{"washington-post", "The Washington Post", "https://washingtonpost.com", "Quốc tế", "Tin tức Toàn cầu, Chính trị & Tổng hợp", "en"},
	{"time", "Time Magazine", "https://time.com", "Quốc tế", "Tin tức Toàn cầu, Chính trị & Tổng hợp", "en"},
	{"deutsche-welle", "Deutsche Welle (DW)", "https://dw.com", "Quốc tế", "Tin tức Toàn cầu, Chính trị & Tổng hợp", "en"},
	{"france24", "France 24", "https://france24.com", "Quốc tế", "Tin tức Toàn cầu, Chính trị & Tổng hợp", "en"},
	{"nikkei-asia", "Nikkei Asia", "https://nikkei.com", "Quốc tế", "Tin tức Toàn cầu, Chính trị & Tổng hợp", "en"},
	{"straits-times", "The Straits Times", "https://straitstimes.com", "Quốc tế", "Tin tức Toàn cầu, Chính trị & Tổng hợp", "en"},
	{"scmp", "South China Morning Post (SCMP)", "https://scmp.com", "Quốc tế", "Tin tức Toàn cầu, Chính trị & Tổng hợp", "en"},
	{"bloomberg", "Bloomberg", "https://bloomberg.com", "Quốc tế", "Tài chính, Kinh doanh & Thị trường Toàn cầu", "en"},
	{"wall-street-journal", "The Wall Street Journal (WSJ)", "https://wsj.com", "Quốc tế", "Tài chính, Kinh doanh & Thị trường Toàn cầu", "en"},
	{"the-economist", "The Economist", "https://economist.com", "Quốc tế", "Tài chính, Kinh doanh & Thị trường Toàn cầu", "en"},
	{"financial-times", "Financial Times (FT)", "https://ft.com", "Quốc tế", "Tài chính, Kinh doanh & Thị trường Toàn cầu", "en"},
	{"forbes", "Forbes", "https://forbes.com", "Quốc tế", "Tài chính, Kinh doanh & Thị trường Toàn cầu", "en"},
	{"business-insider", "Business Insider", "https://businessinsider.com", "Quốc tế", "Tài chính, Kinh doanh & Thị trường Toàn cầu", "en"},
	{"harvard-business-review", "Harvard Business Review (HBR)", "https://hbr.org", "Quốc tế", "Tài chính, Kinh doanh & Thị trường Toàn cầu", "en"},
	{"techcrunch", "TechCrunch", "https://techcrunch.com", "Quốc tế", "Khoa học, Công nghệ & Đổi mới", "en"},
	{"the-verge", "The Verge", "https://theverge.com", "Quốc tế", "Khoa học, Công nghệ & Đổi mới", "en"},
	{"wired", "Wired", "https://wired.com", "Quốc tế", "Khoa học, Công nghệ & Đổi mới", "en"},
	{"mit-technology-review", "MIT Technology Review", "https://technologyreview.com", "Quốc tế", "Khoa học, Công nghệ & Đổi mới", "en"},
	{"nature", "Nature", "https://nature.com", "Quốc tế", "Khoa học, Công nghệ & Đổi mới", "en"},
	{"national-geographic", "National Geographic", "https://nationalgeographic.com", "Quốc tế", "Khoa học, Công nghệ & Đổi mới", "en"},
}

func newsSourcesPayload() []Object {
	items := make([]Object, 0, len(publisherNewsSources))
	for _, source := range publisherNewsSources {
		items = append(items, Object{
			"id": source.ID, "name": source.Name, "url": source.URL,
			"region": source.Region, "category": source.Category, "language": source.Language,
		})
	}
	return items
}

func (s *Server) publicNewsSources(w http.ResponseWriter, r *http.Request) error {
	if r.Method != http.MethodGet {
		return problem(405, "METHOD_NOT_ALLOWED", "GET required")
	}
	sources := newsSourcesPayload()
	for _, source := range publisherNewsSources {
		u, err := url.Parse(source.URL)
		if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil {
			return problem(500, "NEWS_SOURCE_INVALID", "News source catalog contains an invalid publisher URL")
		}
	}
	w.Header().Set("Cache-Control", "private, max-age=300")
	send(w, 200, Object{"items": sources, "count": len(sources), "content_policy": "headline links and publisher homepages only; article text/audio requires separately confirmed rights"})
	return nil
}
